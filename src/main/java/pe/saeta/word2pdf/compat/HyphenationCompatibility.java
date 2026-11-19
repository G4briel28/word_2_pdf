package pe.saeta.word2pdf.compat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.validation.OoxmlPackageReader;
import pe.saeta.word2pdf.validation.SecureXml;

/**
 * Corrige una diferencia conocida entre Word y ONLYOFFICE 10 en la división automática de palabras.
 * <p>
 * Si {@code word/settings.xml} activa {@code <w:autoHyphenation/>} pero los valores por defecto de párrafo de
 * {@code word/styles.xml} ({@code <w:pPrDefault>}) declaran {@code <w:suppressAutoHyphens/>}, Word <b>no</b> divide
 * ninguna palabra, mientras que ONLYOFFICE ignora ese valor por defecto y sí las divide. Eso cambia los saltos de
 * línea y la paginación.
 * <p>
 * Solo en ese caso, y solo si ningún estilo ni párrafo vuelve a permitir la división, se envía a ONLYOFFICE una
 * <b>copia en memoria</b> del DOCX sin {@code <w:autoHyphenation/>}. El resultado en Word es el mismo, porque
 * Word ya no dividía ninguna palabra. El resto de partes del paquete se copian sin cambios. No se escribe
 * nada en disco, y ante cualquier duda o error se usa el DOCX original.
 * <p>
 * Es una excepción, aprobada expresamente, a la regla general de no modificar el DOCX. Se desactiva con
 * {@code APP_COMPAT_ALIGN_HYPHENATION=false}.
 */
@Component
public class HyphenationCompatibility {

    private static final Logger log = LoggerFactory.getLogger(HyphenationCompatibility.class);

    static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String SETTINGS = "word/settings.xml";
    static final String STYLES = "word/styles.xml";
    /** Partes XML directamente bajo {@code word/} (documento, encabezados, pies, notas, numeración...). */
    private static final Pattern WORD_PART = Pattern.compile("word/[^/]+\\.xml");
    private static final int MAX_PART_BYTES = 64 * 1024 * 1024;
    private static final Pattern XML_DECL_ENCODING = Pattern.compile("^<\\?xml[^>]*encoding=\"([^\"]+)\"");

    /**
     * @param docx     bytes que deben enviarse al motor (el original si no se ajustó)
     * @param adjusted true si se envía una copia con la división automática desactivada
     */
    public record Result(byte[] docx, boolean adjusted) {
    }

    private final boolean enabled;

    public HyphenationCompatibility(ConversionProperties properties) {
        this.enabled = properties.compat().alignHyphenationWithWord();
    }

    /** Devuelve los bytes a convertir. Nunca modifica el arreglo recibido. */
    public Result apply(byte[] docx) {
        if (!enabled) {
            return new Result(docx, false);
        }
        try {
            Map<String, byte[]> parts = OoxmlPackageReader.readParts(docx,
                    n -> WORD_PART.matcher(n).matches(), MAX_PART_BYTES);
            byte[] settings = parts.get(SETTINGS);
            byte[] styles = parts.get(STYLES);
            if (settings == null || styles == null || !parts.containsKey("word/document.xml")) {
                return new Result(docx, false);
            }
            String prefix = autoHyphenationPrefix(settings);
            if (prefix == null || !suppressedByDefaultOnly(styles)) {
                return new Result(docx, false);
            }
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                String name = part.getKey();
                if (!name.equals(SETTINGS) && !name.equals(STYLES) && reEnablesHyphenation(part.getValue())) {
                    return new Result(docx, false);
                }
            }
            byte[] newSettings = removeAutoHyphenation(settings, prefix);
            if (newSettings == null) {
                return new Result(docx, false);
            }
            byte[] adjusted = replacePart(docx, SETTINGS, newSettings);
            log.info("Ajuste de compatibilidad aplicado: división automática de palabras desactivada, como en Word");
            return new Result(adjusted, true);
        } catch (Exception e) {
            log.debug("No se aplicó el ajuste de división de palabras: {}", e.getClass().getSimpleName());
            return new Result(docx, false);
        }
    }

    /**
     * Prefijo XML de {@code <w:autoHyphenation>} si está activado en settings.xml; {@code null} si no lo está.
     */
    static String autoHyphenationPrefix(byte[] settingsXml) throws XMLStreamException {
        XMLStreamReader r = SecureXml.reader(settingsXml);
        try {
            String prefix = null;
            int count = 0;
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && W_NS.equals(r.getNamespaceURI())
                        && "autoHyphenation".equals(r.getLocalName())) {
                    count++;
                    if (!isOn(r)) {
                        return null;
                    }
                    prefix = r.getPrefix() == null ? "" : r.getPrefix();
                }
            }
            return count == 1 ? prefix : null;
        } finally {
            r.close();
        }
    }

    /**
     * True si {@code <w:pPrDefault>} suprime la división y ningún estilo vuelve a permitirla
     * ({@code <w:suppressAutoHyphens w:val="0"/>}).
     */
    static boolean suppressedByDefaultOnly(byte[] stylesXml) throws XMLStreamException {
        XMLStreamReader r = SecureXml.reader(stylesXml);
        try {
            boolean inPPrDefault = false;
            boolean defaultSuppresses = false;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT && W_NS.equals(r.getNamespaceURI())) {
                    if ("pPrDefault".equals(r.getLocalName())) {
                        inPPrDefault = true;
                    } else if ("suppressAutoHyphens".equals(r.getLocalName())) {
                        boolean on = isOn(r);
                        if (inPPrDefault) {
                            defaultSuppresses = on;
                        } else if (!on) {
                            return false;
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && W_NS.equals(r.getNamespaceURI())
                        && "pPrDefault".equals(r.getLocalName())) {
                    inPPrDefault = false;
                }
            }
            return defaultSuppresses;
        } finally {
            r.close();
        }
    }

    /** True si la parte contiene algún {@code <w:suppressAutoHyphens>} desactivado (vuelve a permitir la división). */
    static boolean reEnablesHyphenation(byte[] xml) throws XMLStreamException {
        XMLStreamReader r = SecureXml.reader(xml);
        try {
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && W_NS.equals(r.getNamespaceURI())
                        && "suppressAutoHyphens".equals(r.getLocalName()) && !isOn(r)) {
                    return true;
                }
            }
            return false;
        } finally {
            r.close();
        }
    }

    /**
     * Quita el único elemento {@code autoHyphenation} del texto de settings.xml, sin tocar nada más.
     * Devuelve {@code null} si no puede hacerlo con seguridad.
     */
    static byte[] removeAutoHyphenation(byte[] settingsXml, String prefix) throws XMLStreamException {
        if (settingsXml.length >= 2 && ((settingsXml[0] == (byte) 0xFE && settingsXml[1] == (byte) 0xFF)
                || (settingsXml[0] == (byte) 0xFF && settingsXml[1] == (byte) 0xFE))) {
            return null; // UTF-16: no se ajusta
        }
        String xml = new String(settingsXml, StandardCharsets.UTF_8);
        Matcher decl = XML_DECL_ENCODING.matcher(xml.startsWith("﻿") ? xml.substring(1) : xml);
        if (decl.find() && !decl.group(1).equalsIgnoreCase("UTF-8")) {
            return null;
        }
        String name = Pattern.quote(prefix.isEmpty() ? "autoHyphenation" : prefix + ":autoHyphenation");
        Pattern element = Pattern.compile("<" + name + "(?:\\s[^<>]*)?/>|<" + name + "(?:\\s[^<>]*)?>\\s*</" + name + "\\s*>");
        Matcher m = element.matcher(xml);
        if (!m.find()) {
            return null;
        }
        String result = xml.substring(0, m.start()) + xml.substring(m.end());
        if (element.matcher(result).find()) {
            return null;
        }
        byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
        // Comprobación final: XML bien formado y sin autoHyphenation activado.
        return autoHyphenationPrefix(bytes) == null && wellFormed(bytes) ? bytes : null;
    }

    private static boolean wellFormed(byte[] xml) {
        try {
            XMLStreamReader r = SecureXml.reader(xml);
            try {
                while (r.hasNext()) {
                    r.next();
                }
                return true;
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            return false;
        }
    }

    /** Copia el paquete ZIP en memoria, en el mismo orden, sustituyendo solo una parte. */
    static byte[] replacePart(byte[] zip, String partName, byte[] newContent) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(zip.length + 1024);
        byte[] chunk = new byte[16 * 1024];
        boolean replaced = false;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip));
             ZipOutputStream zos = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getTime() != -1) {
                    copy.setTime(entry.getTime());
                }
                zos.putNextEntry(copy);
                if (entry.getName().equals(partName)) {
                    zos.write(newContent);
                    replaced = true;
                } else {
                    int n;
                    while ((n = zis.read(chunk)) > 0) {
                        zos.write(chunk, 0, n);
                    }
                }
                zos.closeEntry();
            }
        }
        if (!replaced) {
            throw new IOException("Parte no encontrada");
        }
        return out.toByteArray();
    }

    /** Valor ST_OnOff de OOXML: sin {@code w:val} equivale a activado. */
    private static boolean isOn(XMLStreamReader r) {
        String val = r.getAttributeValue(W_NS, "val");
        return val == null || !(val.equals("0") || val.equalsIgnoreCase("false") || val.equalsIgnoreCase("off"));
    }
}
