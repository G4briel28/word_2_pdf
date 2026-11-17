package pe.saeta.word2pdf.fidelity;

import java.util.List;
import java.util.Set;

/**
 * Resultado de los controles de fidelidad post-conversión. Son heurísticas: un informe sin avisos
 * NO garantiza fidelidad visual; un aviso indica que conviene revisar el PDF.
 *
 * @param warnings               códigos de aviso ({@link #PAGE_SIZE_MISMATCH}, {@link #FONT_SUBSTITUTION_SUSPECTED})
 * @param suspectedMissingFonts  fuentes usadas por el DOCX que no aparecen en el PDF
 * @param pageSizeIssues         descripción de páginas cuyo tamaño no coincide con ninguna sección del DOCX
 */
public record FidelityReport(List<String> warnings, Set<String> suspectedMissingFonts, List<String> pageSizeIssues) {

    public static final String PAGE_SIZE_MISMATCH = "PAGE_SIZE_MISMATCH";
    public static final String FONT_SUBSTITUTION_SUSPECTED = "FONT_SUBSTITUTION_SUSPECTED";

    public static FidelityReport empty() {
        return new FidelityReport(List.of(), Set.of(), List.of());
    }

    public boolean hasWarning(String code) {
        return warnings.contains(code);
    }
}
