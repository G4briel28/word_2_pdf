package pe.saeta.word2pdf.fidelity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.validation.OoxmlPackageReader;
import pe.saeta.word2pdf.validation.SecureXml;

/**
 * Lee (sin modificar) la maquetación declarada en un DOCX: tamaño de página/orientación/márgenes por sección
 * y fuentes latinas efectivamente referenciadas (contenido, estilos usados y su cadena basedOn, docDefaults
 * y fuentes del tema). Es best-effort: si algo no se puede leer, devuelve lo obtenido sin fallar la conversión.
 */
@Component
public class DocxLayoutInspector {

    private static final Logger log = LoggerFactory.getLogger(DocxLayoutInspector.class);

    static final String W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static final String A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final Pattern CONTENT_PART = Pattern.compile(
            "word/(document|header\\d*|footer\\d*|footnotes|endnotes)\\.xml");
    private static final Pattern THEME_PART = Pattern.compile("word/theme/theme\\d*\\.xml");
    private static final int MAX_PART_BYTES = 64 * 1024 * 1024;

    public DocxLayout inspect(byte[] docx) {
        try {
            Map<String, byte[]> parts = OoxmlPackageReader.readParts(docx,
                    n -> CONTENT_PART.matcher(n).matches() || THEME_PART.matcher(n).matches()
                            || n.equals("word/styles.xml"),
                    MAX_PART_BYTES);
            List<DocxLayout.Section> sections = new ArrayList<>();
            FontRefs contentRefs = new FontRefs();
            for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                if (CONTENT_PART.matcher(part.getKey()).matches()) {
                    parseContent(part.getValue(), part.getKey().equals("word/document.xml") ? sections : null,
                            contentRefs);
                }
            }
            Set<String> fonts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            fonts.addAll(contentRefs.fonts);
            boolean minorTheme = contentRefs.minorTheme;
            boolean majorTheme = contentRefs.majorTheme;

            byte[] styles = parts.get("word/styles.xml");
            if (styles != null) {
                FontRefs styleRefs = resolveStyles(styles, contentRefs.styleIds);
                fonts.addAll(styleRefs.fonts);
                minorTheme |= styleRefs.minorTheme;
                majorTheme |= styleRefs.majorTheme;
            }
            if (minorTheme || majorTheme) {
                for (Map.Entry<String, byte[]> part : parts.entrySet()) {
                    if (THEME_PART.matcher(part.getKey()).matches()) {
                        fonts.addAll(themeFonts(part.getValue(), minorTheme, majorTheme));
                        break;
                    }
                }
            }
            fonts.removeIf(f -> f.isBlank() || f.startsWith("+"));
            return new DocxLayout(List.copyOf(sections), Set.copyOf(fonts));
        } catch (Exception e) {
            log.debug("No se pudo inspeccionar la maquetación del DOCX: {}", e.getClass().getSimpleName());
            return new DocxLayout(List.of(), Set.of());
        }
    }

    /** Referencias a fuentes y estilos encontradas en un conjunto de partes. */
    private static final class FontRefs {
        final Set<String> fonts = new HashSet<>();
        final Set<String> styleIds = new HashSet<>();
        boolean minorTheme;
        boolean majorTheme;

        void addRFonts(XMLStreamReader r) {
            addFont(r.getAttributeValue(W_NS, "ascii"));
            addFont(r.getAttributeValue(W_NS, "hAnsi"));
            addTheme(r.getAttributeValue(W_NS, "asciiTheme"));
            addTheme(r.getAttributeValue(W_NS, "hAnsiTheme"));
        }

        private void addFont(String font) {
            if (font != null && !font.isBlank()) {
                fonts.add(font.trim());
            }
        }

        private void addTheme(String theme) {
            if (theme == null) {
                return;
            }
            if (theme.startsWith("minor")) {
                minorTheme = true;
            } else if (theme.startsWith("major")) {
                majorTheme = true;
            }
        }
    }

    private static void parseContent(byte[] xml, List<DocxLayout.Section> sections, FontRefs refs)
            throws XMLStreamException {
        XMLStreamReader r = SecureXml.reader(xml);
        try {
            SectionBuilder current = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT && W_NS.equals(r.getNamespaceURI())) {
                    switch (r.getLocalName()) {
                        case "rFonts" -> refs.addRFonts(r);
                        case "pStyle", "rStyle", "tblStyle" -> {
                            String id = r.getAttributeValue(W_NS, "val");
                            if (id != null) {
                                refs.styleIds.add(id);
                            }
                        }
                        case "sectPr" -> current = sections != null ? new SectionBuilder() : null;
                        case "pgSz" -> {
                            if (current != null) {
                                current.width = intAttr(r, "w");
                                current.height = intAttr(r, "h");
                                current.landscape = "landscape".equals(r.getAttributeValue(W_NS, "orient"));
                            }
                        }
                        case "pgMar" -> {
                            if (current != null) {
                                current.top = intAttr(r, "top");
                                current.bottom = intAttr(r, "bottom");
                                current.left = intAttr(r, "left");
                                current.right = intAttr(r, "right");
                            }
                        }
                        default -> {
                            // sin interés
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && current != null
                        && W_NS.equals(r.getNamespaceURI()) && "sectPr".equals(r.getLocalName())) {
                    if (current.width != null && current.height != null) {
                        boolean landscape = current.landscape || current.width > current.height;
                        sections.add(new DocxLayout.Section(current.width, current.height, landscape,
                                current.top, current.bottom, current.left, current.right));
                    }
                    current = null;
                }
            }
        } finally {
            r.close();
        }
    }

    private static final class SectionBuilder {
        Integer width;
        Integer height;
        boolean landscape;
        Integer top;
        Integer bottom;
        Integer left;
        Integer right;
    }

    private record StyleDef(String basedOn, FontRefs refs) {
    }

    /**
     * Considera docDefaults, estilos por defecto (párrafo/tabla) y los estilos referenciados con su cadena basedOn.
     */
    private static FontRefs resolveStyles(byte[] stylesXml, Set<String> referencedIds) throws XMLStreamException {
        FontRefs result = new FontRefs();
        Map<String, StyleDef> styles = new HashMap<>();
        Set<String> used = new HashSet<>(referencedIds);
        XMLStreamReader r = SecureXml.reader(stylesXml);
        try {
            boolean inDocDefaults = false;
            String currentId = null;
            FontRefs currentRefs = null;
            String currentBasedOn = null;
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT && W_NS.equals(r.getNamespaceURI())) {
                    switch (r.getLocalName()) {
                        case "docDefaults" -> inDocDefaults = true;
                        case "style" -> {
                            currentId = r.getAttributeValue(W_NS, "styleId");
                            currentRefs = new FontRefs();
                            currentBasedOn = null;
                            String type = r.getAttributeValue(W_NS, "type");
                            String isDefault = r.getAttributeValue(W_NS, "default");
                            if (currentId != null && ("1".equals(isDefault) || "true".equals(isDefault))
                                    && ("paragraph".equals(type) || "table".equals(type))) {
                                used.add(currentId);
                            }
                        }
                        case "basedOn" -> currentBasedOn = r.getAttributeValue(W_NS, "val");
                        case "rFonts" -> {
                            if (inDocDefaults) {
                                result.addRFonts(r);
                            } else if (currentRefs != null) {
                                currentRefs.addRFonts(r);
                            }
                        }
                        default -> {
                            // sin interés
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT && W_NS.equals(r.getNamespaceURI())) {
                    if ("docDefaults".equals(r.getLocalName())) {
                        inDocDefaults = false;
                    } else if ("style".equals(r.getLocalName()) && currentId != null) {
                        styles.put(currentId, new StyleDef(currentBasedOn, currentRefs));
                        currentId = null;
                        currentRefs = null;
                    }
                }
            }
        } finally {
            r.close();
        }
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>(used);
        while (!pending.isEmpty()) {
            String id = pending.pop();
            if (!visited.add(id)) {
                continue;
            }
            StyleDef def = styles.get(id);
            if (def == null) {
                continue;
            }
            result.fonts.addAll(def.refs().fonts);
            result.minorTheme |= def.refs().minorTheme;
            result.majorTheme |= def.refs().majorTheme;
            if (def.basedOn() != null) {
                pending.push(def.basedOn());
            }
        }
        return result;
    }

    private static Set<String> themeFonts(byte[] themeXml, boolean minor, boolean major) {
        Set<String> fonts = new HashSet<>();
        try {
            XMLStreamReader r = SecureXml.reader(themeXml);
            try {
                String scheme = null;
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT && A_NS.equals(r.getNamespaceURI())) {
                        switch (r.getLocalName()) {
                            case "majorFont" -> scheme = "major";
                            case "minorFont" -> scheme = "minor";
                            case "latin" -> {
                                String typeface = r.getAttributeValue(null, "typeface");
                                if (typeface != null && (("minor".equals(scheme) && minor)
                                        || ("major".equals(scheme) && major))) {
                                    fonts.add(typeface);
                                }
                            }
                            default -> {
                                // sin interés
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT && A_NS.equals(r.getNamespaceURI())
                            && ("majorFont".equals(r.getLocalName()) || "minorFont".equals(r.getLocalName()))) {
                        scheme = null;
                    }
                }
            } finally {
                r.close();
            }
        } catch (XMLStreamException e) {
            log.debug("Tema ilegible: {}", e.getClass().getSimpleName());
        }
        return fonts;
    }

    private static Integer intAttr(XMLStreamReader r, String name) {
        String value = r.getAttributeValue(W_NS, name);
        if (value == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
