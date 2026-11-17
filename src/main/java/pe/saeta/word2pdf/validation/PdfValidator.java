package pe.saeta.word2pdf.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;

/**
 * Validación ESTRUCTURAL del PDF devuelto por el motor (no es una garantía de fidelidad visual):
 * cabecera %PDF-, no vacío, se puede abrir con PDFBox, al menos una página, sin cifrado.
 * Todo se procesa en memoria (sin ficheros temporales de PDFBox).
 */
@Component
public class PdfValidator {

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_SEARCH_WINDOW = 1024;
    private static final int MAX_FORM_DEPTH = 3;

    private final long maxOutputBytes;

    public PdfValidator(ConversionProperties properties) {
        this.maxOutputBytes = properties.maxOutputSize().toBytes();
    }

    public PdfInfo validate(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw invalid("PDF vacío", null);
        }
        if (pdf.length > maxOutputBytes) {
            throw invalid("PDF supera el tamaño máximo de salida", null);
        }
        if (!hasPdfHeader(pdf)) {
            throw invalid("Sin cabecera %PDF-", null);
        }
        try (PDDocument document = Loader.loadPDF(pdf, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
            if (document.isEncrypted()) {
                throw invalid("PDF cifrado", null);
            }
            int pageCount = document.getNumberOfPages();
            if (pageCount < 1) {
                throw invalid("PDF sin páginas", null);
            }
            List<PdfInfo.PageSize> pages = new ArrayList<>(pageCount);
            Set<String> fonts = new TreeSet<>();
            for (PDPage page : document.getPages()) {
                pages.add(visibleSize(page));
                collectFonts(page.getResources(), fonts, 0);
            }
            return new PdfInfo(pageCount, List.copyOf(pages), Set.copyOf(fonts));
        } catch (ConversionException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid("PDFBox no pudo abrir el PDF: " + e.getClass().getSimpleName(), e);
        }
    }

    static boolean hasPdfHeader(byte[] pdf) {
        int limit = Math.min(pdf.length - PDF_MAGIC.length, HEADER_SEARCH_WINDOW);
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < PDF_MAGIC.length; j++) {
                if (pdf[i + j] != PDF_MAGIC[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static PdfInfo.PageSize visibleSize(PDPage page) {
        PDRectangle box = page.getCropBox();
        float w = box.getWidth();
        float h = box.getHeight();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        return rotation == 90 || rotation == 270 ? new PdfInfo.PageSize(h, w) : new PdfInfo.PageSize(w, h);
    }

    private static void collectFonts(PDResources resources, Set<String> fonts, int depth) {
        if (resources == null || depth > MAX_FORM_DEPTH) {
            return;
        }
        for (COSName name : resources.getFontNames()) {
            try {
                PDFont font = resources.getFont(name);
                if (font != null && font.getName() != null) {
                    fonts.add(font.getName());
                }
            } catch (IOException | RuntimeException ignored) {
                // Fuente ilegible: no invalida el PDF, solo se omite del inventario.
            }
        }
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject form) {
                    collectFonts(form.getResources(), fonts, depth + 1);
                }
            } catch (IOException | RuntimeException ignored) {
                // Se omite el XObject ilegible.
            }
        }
    }

    private static ConversionException invalid(String internalReason, Throwable cause) {
        return new ConversionException(ErrorCode.INVALID_PDF_OUTPUT, ErrorCode.INVALID_PDF_OUTPUT.defaultMessage(),
                internalReason, cause);
    }
}
