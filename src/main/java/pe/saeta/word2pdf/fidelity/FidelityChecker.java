package pe.saeta.word2pdf.fidelity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.validation.PdfInfo;

/**
 * Compara lo declarado en el DOCX con el PDF producido:
 * <ul>
 *   <li>Tamaño/orientación de página: cada página del PDF debe coincidir con alguna sección del DOCX.</li>
 *   <li>Fuentes: cada fuente usada en el DOCX debería aparecer en el PDF; si no, se sospecha sustitución
 *       (típico cuando la fuente no está instalada en el contenedor de ONLYOFFICE).</li>
 * </ul>
 */
@Component
public class FidelityChecker {

    private static final int MAX_REPORTED_PAGES = 10;

    private final ConversionProperties.Fidelity config;

    public FidelityChecker(ConversionProperties properties) {
        this.config = properties.fidelity();
    }

    public FidelityReport check(DocxLayout layout, PdfInfo pdf) {
        List<String> warnings = new ArrayList<>();
        List<String> pageIssues = checkPageSizes(layout, pdf);
        if (!pageIssues.isEmpty()) {
            warnings.add(FidelityReport.PAGE_SIZE_MISMATCH);
        }
        Set<String> missingFonts = checkFonts(layout, pdf);
        if (!missingFonts.isEmpty()) {
            warnings.add(FidelityReport.FONT_SUBSTITUTION_SUSPECTED);
        }
        return new FidelityReport(List.copyOf(warnings), Set.copyOf(missingFonts), List.copyOf(pageIssues));
    }

    /** Lanza error 422 si el informe incumple una regla configurada como bloqueante. */
    public void enforce(FidelityReport report) {
        if (config.failOnPageSizeMismatch() && report.hasWarning(FidelityReport.PAGE_SIZE_MISMATCH)) {
            throw new ConversionException(ErrorCode.FIDELITY_CHECK_FAILED,
                    "El tamaño de página del PDF no coincide con el del documento Word.", "PAGE_SIZE_MISMATCH");
        }
        if (config.failOnFontSubstitution() && report.hasWarning(FidelityReport.FONT_SUBSTITUTION_SUSPECTED)) {
            throw new ConversionException(ErrorCode.FIDELITY_CHECK_FAILED,
                    "Fuentes del documento no disponibles en el motor: " + String.join(", ", report.suspectedMissingFonts()),
                    "FONT_SUBSTITUTION_SUSPECTED");
        }
    }

    private List<String> checkPageSizes(DocxLayout layout, PdfInfo pdf) {
        List<String> issues = new ArrayList<>();
        if (layout.sections().isEmpty()) {
            return issues;
        }
        double tol = config.pageSizeTolerancePoints();
        for (int i = 0; i < pdf.pages().size(); i++) {
            PdfInfo.PageSize page = pdf.pages().get(i);
            boolean matches = layout.sections().stream().anyMatch(s ->
                    Math.abs(s.pageWidthPt() - page.widthPt()) <= tol && Math.abs(s.pageHeightPt() - page.heightPt()) <= tol);
            if (!matches) {
                if (issues.size() < MAX_REPORTED_PAGES) {
                    issues.add("página " + (i + 1) + ": " + page + " (esperado: " + expected(layout) + ")");
                } else {
                    break;
                }
            }
        }
        return issues;
    }

    private static String expected(DocxLayout layout) {
        return layout.sections().stream()
                .map(s -> String.format(Locale.ROOT, "%.1fx%.1fpt", s.pageWidthPt(), s.pageHeightPt()))
                .distinct().collect(Collectors.joining(" | "));
    }

    private static Set<String> checkFonts(DocxLayout layout, PdfInfo pdf) {
        Set<String> missing = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (layout.fonts().isEmpty() || pdf.fontNames().isEmpty()) {
            return missing;
        }
        Set<String> pdfFonts = pdf.fontNames().stream().map(FidelityChecker::normalizeFontName)
                .collect(Collectors.toSet());
        for (String font : layout.fonts()) {
            if (!pdfFonts.contains(normalizeFontName(font))) {
                missing.add(font);
            }
        }
        return missing;
    }

    /**
     * Normaliza nombres de fuente de Word y nombres PostScript del PDF para poder compararlos:
     * {@code "ABCDEF+TimesNewRomanPS-BoldMT"} y {@code "Times New Roman"} → {@code "timesnewroman"}.
     */
    static String normalizeFontName(String name) {
        String n = name.trim();
        int plus = n.indexOf('+');
        if (plus == 6) {
            n = n.substring(7);
        }
        int comma = n.indexOf(',');
        if (comma > 0) {
            n = n.substring(0, comma);
        }
        int dash = n.indexOf('-');
        if (dash > 0) {
            n = n.substring(0, dash);
        }
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        n = n.replaceAll("(bold|italic|oblique|regular)+$", "");
        for (String suffix : new String[] {"psmt", "mt", "ps"}) {
            if (n.endsWith(suffix) && n.length() > suffix.length() + 3) {
                n = n.substring(0, n.length() - suffix.length());
                break;
            }
        }
        return n;
    }
}
