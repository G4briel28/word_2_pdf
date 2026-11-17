package pe.saeta.word2pdf.validation;

import java.util.List;
import java.util.Set;

/**
 * Datos estructurales de un PDF validado.
 *
 * @param pageCount número de páginas
 * @param pages     tamaño visible de cada página en puntos (1/72"), con la rotación aplicada
 * @param fontNames nombres de fuentes presentes en los recursos de página (pueden llevar prefijo de subconjunto)
 */
public record PdfInfo(int pageCount, List<PageSize> pages, Set<String> fontNames) {

    public record PageSize(float widthPt, float heightPt) {
        public boolean isLandscape() {
            return widthPt > heightPt;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT, "%.1fx%.1fpt", widthPt, heightPt);
        }
    }
}
