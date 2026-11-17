package pe.saeta.word2pdf.validation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;

/**
 * Valida que los bytes recibidos sean realmente un DOCX (WordprocessingML) sano, sin confiar en
 * extensiones ni en el Content-Type declarado por el cliente.
 * <ul>
 *   <li>Firma ZIP y presencia del directorio central.</li>
 *   <li>Detección de .doc (OLE/CFB), DOCX cifrado (también OLE), PDF, RTF.</li>
 *   <li>Protección anti zip-bomb: entradas máximas, bytes descomprimidos reales y ratio de compresión.</li>
 *   <li>Rutas peligrosas o duplicadas dentro del ZIP.</li>
 *   <li>[Content_Types].xml con parte principal WordprocessingML (rechaza .docm, .dotx, xlsx, pptx).</li>
 * </ul>
 * El documento no se modifica.
 */
@Component
public class DocxValidator {

    static final String DOCX_MAIN = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String CONTENT_TYPES = "[Content_Types].xml";
    private static final byte[] ZIP_LOCAL_HEADER = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_END_OF_CENTRAL_DIR = {0x50, 0x4B, 0x05, 0x06};
    private static final byte[] OLE_CFB = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final long RATIO_CHECK_MIN_BYTES = 1024 * 1024;
    private static final int MAX_CONTENT_TYPES_BYTES = 1024 * 1024;

    private final long maxInputBytes;
    private final ConversionProperties.Docx limits;

    public DocxValidator(ConversionProperties properties) {
        this.maxInputBytes = properties.maxInputSize().toBytes();
        this.limits = properties.docx();
    }

    public void validate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ConversionException(ErrorCode.EMPTY_DOCUMENT);
        }
        if (bytes.length > maxInputBytes) {
            throw new ConversionException(ErrorCode.DOCUMENT_TOO_LARGE);
        }
        checkSignature(bytes);
        ZipScan scan = scanZip(bytes);
        if (scan.contentTypesXml() == null) {
            throw invalid("Falta [Content_Types].xml");
        }
        String mainPart = findMainDocumentPart(scan.contentTypesXml());
        if (!scan.entryNames().contains(mainPart)) {
            throw invalid("La parte principal declarada no existe en el paquete");
        }
    }

    private void checkSignature(byte[] bytes) {
        if (startsWith(bytes, OLE_CFB)) {
            throw new ConversionException(ErrorCode.UNSUPPORTED_FORMAT,
                    "Formato no soportado: documento Word 97-2003 (.doc) o DOCX cifrado/protegido con contraseña.",
                    "Firma OLE/CFB");
        }
        if (!startsWith(bytes, ZIP_LOCAL_HEADER)) {
            throw new ConversionException(ErrorCode.UNSUPPORTED_FORMAT, ErrorCode.UNSUPPORTED_FORMAT.defaultMessage(),
                    "Sin firma ZIP");
        }
        if (!hasEndOfCentralDirectory(bytes)) {
            throw invalid("ZIP truncado: sin directorio central");
        }
    }

    private record ZipScan(Set<String> entryNames, byte[] contentTypesXml) {
    }

    /**
     * Recorre el ZIP completo contando bytes realmente descomprimidos (no los declarados en cabeceras).
     */
    private ZipScan scanZip(byte[] bytes) {
        Set<String> entryNames = new HashSet<>();
        Set<String> lowerNames = new HashSet<>();
        byte[] contentTypesXml = null;
        long totalUncompressed = 0;
        int entries = 0;
        byte[] chunk = new byte[16 * 1024];
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                entries++;
                if (entries > limits.maxEntries()) {
                    throw invalid("Demasiadas entradas en el ZIP");
                }
                String name = entry.getName();
                if (!isSafeName(name)) {
                    throw invalid("Ruta de entrada ZIP no válida");
                }
                if (!lowerNames.add(name.toLowerCase(java.util.Locale.ROOT))) {
                    throw invalid("Entrada ZIP duplicada");
                }
                boolean keep = CONTENT_TYPES.equals(name);
                ByteArrayOutputStream kept = keep ? new ByteArrayOutputStream() : null;
                long entryBytes = 0;
                int n;
                while ((n = zis.read(chunk)) > 0) {
                    entryBytes += n;
                    totalUncompressed += n;
                    if (totalUncompressed > limits.maxUncompressedSize().toBytes()) {
                        throw invalid("Tamaño descomprimido excesivo (posible zip bomb)");
                    }
                    if (keep) {
                        if (kept.size() + n > MAX_CONTENT_TYPES_BYTES) {
                            throw invalid("[Content_Types].xml excesivo");
                        }
                        kept.write(chunk, 0, n);
                    }
                }
                long compressed = entry.getCompressedSize();
                if (entryBytes > RATIO_CHECK_MIN_BYTES && compressed > 0
                        && entryBytes / compressed > limits.maxCompressionRatio()) {
                    throw invalid("Ratio de compresión excesivo (posible zip bomb)");
                }
                entryNames.add(name);
                if (keep) {
                    contentTypesXml = kept.toByteArray();
                }
            }
        } catch (ConversionException e) {
            throw e;
        } catch (ZipException e) {
            throw new ConversionException(ErrorCode.INVALID_DOCX, ErrorCode.INVALID_DOCX.defaultMessage(),
                    "ZIP mal formado", e);
        } catch (IOException e) {
            throw new ConversionException(ErrorCode.INVALID_DOCX, ErrorCode.INVALID_DOCX.defaultMessage(),
                    "Error leyendo ZIP", e);
        }
        if (entries == 0) {
            throw invalid("ZIP sin entradas");
        }
        return new ZipScan(entryNames, contentTypesXml);
    }

    private String findMainDocumentPart(byte[] contentTypesXml) {
        String docxMain = null;
        String otherMain = null;
        try {
            XMLStreamReader reader = SecureXml.reader(contentTypesXml);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT && "Override".equals(reader.getLocalName())) {
                        String partName = reader.getAttributeValue(null, "PartName");
                        String contentType = reader.getAttributeValue(null, "ContentType");
                        if (partName == null || contentType == null) {
                            continue;
                        }
                        if (DOCX_MAIN.equals(contentType)) {
                            docxMain = partName;
                        } else if (contentType.endsWith(".main+xml")) {
                            otherMain = contentType;
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException e) {
            throw new ConversionException(ErrorCode.INVALID_DOCX, ErrorCode.INVALID_DOCX.defaultMessage(),
                    "[Content_Types].xml no es XML válido", e);
        }
        if (docxMain != null) {
            return docxMain.startsWith("/") ? docxMain.substring(1) : docxMain;
        }
        if (otherMain != null) {
            String detail = otherMain.contains("macroEnabled")
                    ? "Formato no soportado: documentos con macros (.docm/.dotm) no se aceptan."
                    : "Formato no soportado: el paquete OOXML no es un documento DOCX.";
            throw new ConversionException(ErrorCode.UNSUPPORTED_FORMAT, detail, "Tipo principal: " + otherMain);
        }
        throw invalid("No se encontró la parte principal WordprocessingML");
    }

    private static boolean isSafeName(String name) {
        return !name.isEmpty() && name.length() <= 512
                && !name.startsWith("/") && !name.startsWith("\\")
                && !name.contains("..") && !name.contains(":") && name.indexOf('\0') < 0;
    }

    private static boolean hasEndOfCentralDirectory(byte[] bytes) {
        // El registro EOCD mide 22 bytes + comentario opcional de hasta 65535 bytes.
        int min = Math.max(0, bytes.length - 22 - 65535);
        for (int i = bytes.length - 22; i >= min; i--) {
            if (bytes[i] == ZIP_END_OF_CENTRAL_DIR[0] && bytes[i + 1] == ZIP_END_OF_CENTRAL_DIR[1]
                    && bytes[i + 2] == ZIP_END_OF_CENTRAL_DIR[2] && bytes[i + 3] == ZIP_END_OF_CENTRAL_DIR[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static ConversionException invalid(String internalReason) {
        return new ConversionException(ErrorCode.INVALID_DOCX, ErrorCode.INVALID_DOCX.defaultMessage(), internalReason);
    }
}
