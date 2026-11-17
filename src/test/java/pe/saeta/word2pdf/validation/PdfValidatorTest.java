package pe.saeta.word2pdf.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.support.TestConfigs;
import pe.saeta.word2pdf.support.TestPdfs;

class PdfValidatorTest {

    private final PdfValidator validator = new PdfValidator(TestConfigs.conversion());

    @Test
    void validPdfReturnsPagesSizesAndFonts() {
        PdfInfo info = validator.validate(TestPdfs.a4(3));
        assertThat(info.pageCount()).isEqualTo(3);
        assertThat(info.pages()).allSatisfy(p -> {
            assertThat(p.widthPt()).isCloseTo(595.3f, org.assertj.core.data.Offset.offset(0.5f));
            assertThat(p.heightPt()).isCloseTo(841.9f, org.assertj.core.data.Offset.offset(0.5f));
        });
        assertThat(info.fontNames()).contains("Helvetica");
    }

    @Test
    void landscapePageIsDetected() {
        PDRectangle landscapeLetter = new PDRectangle(PDRectangle.LETTER.getHeight(), PDRectangle.LETTER.getWidth());
        PdfInfo info = validator.validate(TestPdfs.pages(1, landscapeLetter));
        assertThat(info.pages().get(0).isLandscape()).isTrue();
    }

    @Test
    void rejectsEmptyOrNull() {
        assertInvalid(null);
        assertInvalid(new byte[0]);
    }

    @Test
    void rejectsNonPdf() {
        assertInvalid("esto no es un pdf".getBytes(StandardCharsets.UTF_8));
        assertInvalid("{\"error\":-3}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectsPdfHeaderWithGarbage() {
        assertInvalid("%PDF-1.7\n garbage garbage garbage".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void rejectsPdfWithoutPages() {
        assertInvalid(TestPdfs.noPages());
    }

    private void assertInvalid(byte[] pdf) {
        assertThatThrownBy(() -> validator.validate(pdf))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PDF_OUTPUT));
    }
}
