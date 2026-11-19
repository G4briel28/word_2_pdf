package pe.saeta.word2pdf.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import pe.saeta.word2pdf.compat.HyphenationCompatibility;
import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.fidelity.DocxLayoutInspector;
import pe.saeta.word2pdf.fidelity.FidelityChecker;
import pe.saeta.word2pdf.fidelity.FidelityReport;
import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.support.SampleDocxGenerator.Hyphenation;
import pe.saeta.word2pdf.support.TestConfigs;
import pe.saeta.word2pdf.support.TestPdfs;
import pe.saeta.word2pdf.validation.DocxValidator;
import pe.saeta.word2pdf.validation.PdfValidator;

@ExtendWith(MockitoExtension.class)
class DefaultDocumentConversionServiceTest {

    @Mock
    private ConversionEngine engine;

    private SimpleMeterRegistry registry;
    private DefaultDocumentConversionService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = build(TestConfigs.conversion());
    }

    private DefaultDocumentConversionService build(ConversionProperties props) {
        when(engine.name()).thenReturn("mock");
        return new DefaultDocumentConversionService(new DocxValidator(props), new PdfValidator(props), engine,
                new ConversionLimiter(props, registry), new DocxLayoutInspector(), new FidelityChecker(props),
                new HyphenationCompatibility(props), props, registry);
    }

    @Test
    void sendsHyphenationAdjustedCopyWhenWordWouldNotHyphenate() {
        byte[] docx = SampleDocxGenerator.hyphenationContract(Hyphenation.ON_SUPPRESSED_BY_DEFAULT);
        byte[] copy = docx.clone();
        when(engine.convertDocxToPdf(any())).thenReturn(TestPdfs.a4(2));

        ConversionResult result = service.convert(docx);

        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(engine).convertDocxToPdf(sent.capture());
        assertThat(sent.getValue()).isNotEqualTo(docx);
        assertThat(new String(SampleDocxGenerator.unzip(sent.getValue()).get("word/settings.xml"), StandardCharsets.UTF_8))
                .doesNotContain("autoHyphenation");
        assertThat(docx).isEqualTo(copy);
        assertThat(result.fidelity().warnings()).contains(FidelityReport.HYPHENATION_ADJUSTED);
        assertThat(registry.get("docconv.compat.adjustments").tag("type", "hyphenation").counter().count())
                .isEqualTo(1);
    }

    @Test
    void sendsOriginalWhenWordAlsoHyphenates() {
        byte[] docx = SampleDocxGenerator.hyphenationContract(Hyphenation.ON);
        when(engine.convertDocxToPdf(any())).thenReturn(TestPdfs.a4(2));

        ConversionResult result = service.convert(docx);

        verify(engine).convertDocxToPdf(docx);
        assertThat(result.fidelity().warnings()).doesNotContain(FidelityReport.HYPHENATION_ADJUSTED);
    }

    @Test
    void returnsPdfBytesAndPassesOriginalBytesUnmodified() {
        byte[] docx = SampleDocxGenerator.fullContract();
        byte[] copy = docx.clone();
        byte[] pdf = TestPdfs.a4(3);
        when(engine.convertDocxToPdf(any())).thenReturn(pdf);

        byte[] result = service.convertDocxToPdf(docx);

        assertThat(result).isSameAs(pdf);
        verify(engine).convertDocxToPdf(docx);
        assertThat(docx).isEqualTo(copy);
        assertThat(registry.get("docconv.conversion.duration").tag("outcome", "SUCCESS").timer().count()).isEqualTo(1);
    }

    @Test
    void reportsPagesAndFidelityWarnings() {
        when(engine.convertDocxToPdf(any())).thenReturn(TestPdfs.a4(4));
        ConversionResult result = service.convert(SampleDocxGenerator.fullContract());
        assertThat(result.pageCount()).isEqualTo(4);
        // El PDF de prueba usa Helvetica: el DOCX declara Times New Roman/Arial -> sustitución sospechada.
        assertThat(result.fidelity().warnings()).contains(FidelityReport.FONT_SUBSTITUTION_SUSPECTED);
        assertThat(result.fidelity().suspectedMissingFonts()).contains("Times New Roman", "Arial");
    }

    @Test
    void invalidDocumentNeverReachesEngine() {
        assertThatThrownBy(() -> service.convertDocxToPdf("no soy un docx".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNSUPPORTED_FORMAT));
        verify(engine, never()).convertDocxToPdf(any());
        assertThat(registry.get("docconv.conversion.duration").tag("outcome", "UNSUPPORTED_FORMAT").timer().count())
                .isEqualTo(1);
    }

    @Test
    void invalidEngineOutputIsRejected() {
        when(engine.convertDocxToPdf(any())).thenReturn("<html>error</html>".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_PDF_OUTPUT));
    }

    @Test
    void engineErrorsPropagateWithTheirCode() {
        when(engine.convertDocxToPdf(any())).thenThrow(new ConversionException(ErrorCode.CONVERSION_TIMEOUT));
        assertThatThrownBy(() -> service.convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONVERSION_TIMEOUT));
    }

    @Test
    void strictFontModeFailsWhenFontsAreSubstituted() {
        DefaultDocumentConversionService strict = build(TestConfigs.conversionWithFidelity(false, true));
        when(engine.convertDocxToPdf(any())).thenReturn(TestPdfs.a4(1));
        assertThatThrownBy(() -> strict.convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FIDELITY_CHECK_FAILED));
    }
}
