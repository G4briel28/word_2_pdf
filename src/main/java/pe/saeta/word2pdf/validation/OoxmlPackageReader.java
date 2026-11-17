package pe.saeta.word2pdf.validation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extrae en memoria partes concretas (XML) de un paquete OOXML ya validado, con límite por parte.
 * No modifica ni reconstruye el documento: solo lectura.
 */
public final class OoxmlPackageReader {

    private OoxmlPackageReader() {
    }

    public static Map<String, byte[]> readParts(byte[] zipBytes, Predicate<String> wanted, int maxPartBytes)
            throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] chunk = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !wanted.test(name)) {
                    continue;
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(chunk)) > 0) {
                    if (out.size() + n > maxPartBytes) {
                        throw new IOException("Parte OOXML supera el límite de lectura");
                    }
                    out.write(chunk, 0, n);
                }
                parts.put(name, out.toByteArray());
            }
        }
        return parts;
    }
}
