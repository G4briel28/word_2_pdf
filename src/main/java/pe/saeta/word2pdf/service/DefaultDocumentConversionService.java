package pe.saeta.word2pdf.service;

import java.time.Duration;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import pe.saeta.word2pdf.compat.HyphenationCompatibility;
import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.fidelity.DocxLayout;
import pe.saeta.word2pdf.fidelity.DocxLayoutInspector;
import pe.saeta.word2pdf.fidelity.FidelityChecker;
import pe.saeta.word2pdf.fidelity.FidelityReport;
import pe.saeta.word2pdf.validation.DocxValidator;
import pe.saeta.word2pdf.validation.PdfInfo;
import pe.saeta.word2pdf.validation.PdfValidator;

/**
 * Orquesta la conversión: validar DOCX → ajuste de compatibilidad (solo división de palabras, ver
 * {@link HyphenationCompatibility}) → obtener cupo → convertir con el motor → validar PDF →
 * controles de fidelidad → métricas. Todo en memoria; no se escribe ningún archivo en disco.
 * Los logs solo contienen tamaños, duración, páginas y códigos (nunca contenido ni nombres de archivo).
 */
@Service
public class DefaultDocumentConversionService implements DocumentConversionService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDocumentConversionService.class);

    private final DocxValidator docxValidator;
    private final PdfValidator pdfValidator;
    private final ConversionEngine engine;
    private final ConversionLimiter limiter;
    private final DocxLayoutInspector layoutInspector;
    private final FidelityChecker fidelityChecker;
    private final HyphenationCompatibility hyphenationCompatibility;
    private final boolean fidelityEnabled;
    private final MeterRegistry registry;
    private final DistributionSummary inputSize;

    public DefaultDocumentConversionService(DocxValidator docxValidator, PdfValidator pdfValidator,
                                            ConversionEngine engine, ConversionLimiter limiter,
                                            DocxLayoutInspector layoutInspector, FidelityChecker fidelityChecker,
                                            HyphenationCompatibility hyphenationCompatibility,
                                            ConversionProperties properties, MeterRegistry registry) {
        this.docxValidator = docxValidator;
        this.pdfValidator = pdfValidator;
        this.engine = engine;
        this.limiter = limiter;
        this.layoutInspector = layoutInspector;
        this.fidelityChecker = fidelityChecker;
        this.hyphenationCompatibility = hyphenationCompatibility;
        this.fidelityEnabled = properties.fidelity().enabled();
        this.registry = registry;
        this.inputSize = DistributionSummary.builder("docconv.input.size").baseUnit("bytes")
                .description("Tamaño de los DOCX recibidos").register(registry);
    }

    @Override
    public byte[] convertDocxToPdf(byte[] docxBytes) {
        return convert(docxBytes).pdf();
    }

    @Override
    public ConversionResult convert(byte[] docxBytes) {
        long start = System.nanoTime();
        String outcome = ErrorCode.INTERNAL_ERROR.name();
        try {
            docxValidator.validate(docxBytes);
            inputSize.record(docxBytes.length);
            DocxLayout layout = fidelityEnabled ? layoutInspector.inspect(docxBytes) : null;
            HyphenationCompatibility.Result compat = hyphenationCompatibility.apply(docxBytes);
            byte[] engineInput = compat.docx();

            byte[] pdf = limiter.execute(() -> engine.convertDocxToPdf(engineInput));

            PdfInfo info = pdfValidator.validate(pdf);
            FidelityReport report = layout != null ? fidelityChecker.check(layout, info) : FidelityReport.empty();
            if (compat.adjusted()) {
                registry.counter("docconv.compat.adjustments", "type", "hyphenation").increment();
                report = report.withWarning(FidelityReport.HYPHENATION_ADJUSTED);
            }
            if (!report.warnings().isEmpty()) {
                log.warn("Avisos de fidelidad warnings={} fuentesSospechosas={} paginas={}",
                        report.warnings(), report.suspectedMissingFonts(), report.pageSizeIssues());
            }
            fidelityChecker.enforce(report);

            Duration duration = Duration.ofNanos(System.nanoTime() - start);
            outcome = "SUCCESS";
            log.info("Conversión completada engine={} inputBytes={} outputBytes={} pages={} durationMs={}",
                    engine.name(), docxBytes.length, pdf.length, info.pageCount(), duration.toMillis());
            return new ConversionResult(pdf, info.pageCount(), report, duration);
        } catch (ConversionException e) {
            outcome = e.errorCode().name();
            throw e;
        } finally {
            Timer.builder("docconv.conversion.duration")
                    .description("Duración de conversiones DOCX→PDF")
                    .tag("engine", engine.name())
                    .tag("outcome", outcome)
                    .register(registry)
                    .record(Duration.ofNanos(System.nanoTime() - start));
        }
    }
}
