package pe.saeta.word2pdf.fidelity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.support.TestConfigs;
import pe.saeta.word2pdf.validation.PdfInfo;

class FidelityTest {

    private final DocxLayoutInspector inspector = new DocxLayoutInspector();

    @Test
    void inspectorReadsSectionsAndUsedFonts() {
        DocxLayout layout = inspector.inspect(SampleDocxGenerator.fullContract());

        assertThat(layout.sections()).hasSize(2);
        DocxLayout.Section a4 = layout.sections().get(0);
        assertThat(a4.pageWidthTwips()).isEqualTo(SampleDocxGenerator.A4_W);
        assertThat(a4.pageHeightTwips()).isEqualTo(SampleDocxGenerator.A4_H);
        assertThat(a4.landscape()).isFalse();
        assertThat(a4.marginLeftTwips()).isEqualTo(1701);
        DocxLayout.Section letter = layout.sections().get(1);
        assertThat(letter.landscape()).isTrue();
        assertThat(letter.pageWidthTwips()).isEqualTo(SampleDocxGenerator.LETTER_H);

        assertThat(layout.fonts()).containsExactlyInAnyOrder("Times New Roman", "Arial");
    }

    @Test
    void inspectorNeverFailsOnGarbage() {
        assertThatCode(() -> inspector.inspect(new byte[] {1, 2, 3})).doesNotThrowAnyException();
        assertThat(inspector.inspect(new byte[] {1, 2, 3}).sections()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "Times New Roman, timesnewroman",
            "ABCDEF+TimesNewRomanPS-BoldMT, timesnewroman",
            "TimesNewRomanPSMT, timesnewroman",
            "Arial-BoldMT, arial",
            "ArialMT, arial",
            "Calibri, calibri",
            "Calibri-Bold, calibri",
            "'Calibri,Bold', calibri",
            "BCDEEE+Carlito, carlito",
            "Arial Narrow, arialnarrow"
    })
    void normalizesFontNames(String raw, String expected) {
        assertThat(FidelityChecker.normalizeFontName(raw)).isEqualTo(expected);
    }

    @Test
    void detectsFontSubstitutionAndAcceptsEmbeddedFonts() {
        FidelityChecker checker = new FidelityChecker(TestConfigs.conversion());
        DocxLayout layout = new DocxLayout(List.of(), Set.of("Calibri", "Times New Roman"));

        FidelityReport substituted = checker.check(layout, pdf(Set.of("AAAAAA+Carlito", "BBBBBB+TimesNewRomanPSMT")));
        assertThat(substituted.warnings()).containsExactly(FidelityReport.FONT_SUBSTITUTION_SUSPECTED);
        assertThat(substituted.suspectedMissingFonts()).containsExactly("Calibri");

        FidelityReport ok = checker.check(layout, pdf(Set.of("AAAAAA+Calibri", "TimesNewRomanPS-BoldMT")));
        assertThat(ok.warnings()).isEmpty();
    }

    @Test
    void detectsPageSizeMismatch() {
        FidelityChecker checker = new FidelityChecker(TestConfigs.conversion());
        DocxLayout layout = new DocxLayout(List.of(new DocxLayout.Section(SampleDocxGenerator.A4_W,
                SampleDocxGenerator.A4_H, false, null, null, null, null)), Set.of());

        PdfInfo a4 = new PdfInfo(1, List.of(new PdfInfo.PageSize(595.3f, 841.9f)), Set.of());
        PdfInfo letter = new PdfInfo(2, List.of(new PdfInfo.PageSize(595.3f, 841.9f), new PdfInfo.PageSize(612f, 792f)), Set.of());

        assertThat(checker.check(layout, a4).warnings()).isEmpty();
        FidelityReport report = checker.check(layout, letter);
        assertThat(report.warnings()).containsExactly(FidelityReport.PAGE_SIZE_MISMATCH);
        assertThat(report.pageSizeIssues()).singleElement().asString().startsWith("página 2");
    }

    @Test
    void enforceFailsOnlyWhenConfigured() {
        FidelityReport report = new FidelityReport(List.of(FidelityReport.FONT_SUBSTITUTION_SUSPECTED), Set.of("Calibri"), List.of());
        assertThatCode(() -> new FidelityChecker(TestConfigs.conversion()).enforce(report)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new FidelityChecker(TestConfigs.conversionWithFidelity(false, true)).enforce(report))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FIDELITY_CHECK_FAILED));
    }

    private static PdfInfo pdf(Set<String> fonts) {
        return new PdfInfo(1, List.of(new PdfInfo.PageSize(595.3f, 841.9f)), fonts);
    }
}
