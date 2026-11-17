package pe.saeta.word2pdf.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** PDFs sintéticos para pruebas (PDFBox). */
public final class TestPdfs {

    private TestPdfs() {
    }

    public static byte[] pages(int count, PDRectangle size) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int i = 1; i <= count; i++) {
                PDPage page = new PDPage(size);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, size.getHeight() - 72);
                    cs.showText("Pagina " + i + " de prueba");
                    cs.endText();
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] a4(int count) {
        return pages(count, PDRectangle.A4);
    }

    /** Un PDF con líneas de texto en posiciones verticales concretas (para la herramienta de comparación). */
    public static byte[] lines(List<String> lines, float startY, float leading) {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = PDRectangle.A4.getHeight() - startY;
                for (String line : lines) {
                    cs.beginText();
                    cs.setFont(font, 12);
                    cs.newLineAtOffset(72, y);
                    cs.showText(line);
                    cs.endText();
                    y -= leading;
                }
            }
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PDF válido sin páginas. */
    public static byte[] noPages() {
        try (PDDocument doc = new PDDocument()) {
            return save(doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] save(PDDocument doc) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        return out.toByteArray();
    }
}
