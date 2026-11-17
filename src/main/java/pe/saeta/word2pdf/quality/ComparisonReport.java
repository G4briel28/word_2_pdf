package pe.saeta.word2pdf.quality;

import java.util.List;

/**
 * Informe de comparación entre un PDF de referencia (exportado desde Microsoft Word) y el PDF candidato
 * (generado por ONLYOFFICE). Puede contener fragmentos breves de texto del documento: trátelo como confidencial.
 */
public record ComparisonReport(
        int referencePages,
        int candidatePages,
        boolean pageCountMatches,
        List<PageComparison> pages,
        TextShift textShift,
        List<AnchorComparison> anchors,
        boolean likelyEquivalent,
        List<String> findings) {

    public record PageComparison(int page, String referenceSize, String candidateSize, boolean sizeMatches,
                                 double pixelDiffRatio, String diffImage) {
    }

    /**
     * @param matchedLines        líneas de texto emparejadas entre ambos PDF
     * @param unmatchedReference  líneas de la referencia sin pareja en el candidato
     * @param maxAbsShiftPt       mayor desplazamiento vertical (puntos) de una línea en la misma página
     * @param meanAbsShiftPt      desplazamiento vertical medio absoluto
     * @param linesBeyondTolerance líneas desplazadas más que la tolerancia
     * @param linesOnDifferentPage líneas que cambiaron de página (reflujo del contenido)
     * @param worstLines          muestra de las líneas más desplazadas (texto truncado)
     */
    public record TextShift(int matchedLines, int unmatchedReference, double maxAbsShiftPt, double meanAbsShiftPt,
                            int linesBeyondTolerance, int linesOnDifferentPage, List<LineShift> worstLines) {
    }

    public record LineShift(String text, int referencePage, int candidatePage, double referenceY, double candidateY,
                            double dyPt) {
    }

    public record AnchorComparison(String anchor, int occurrence, Integer referencePage, Double referenceY,
                                   Integer candidatePage, Double candidateY, Double dyPt) {
    }
}
