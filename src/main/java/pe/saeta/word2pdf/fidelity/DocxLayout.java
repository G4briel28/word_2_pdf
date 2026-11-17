package pe.saeta.word2pdf.fidelity;

import java.util.List;
import java.util.Set;

/**
 * Información de maquetación declarada en el DOCX (solo lectura).
 *
 * @param sections secciones con tamaño de página y márgenes en twips (1/20 pt)
 * @param fonts    fuentes latinas referenciadas por el contenido, estilos usados y tema
 */
public record DocxLayout(List<Section> sections, Set<String> fonts) {

    public record Section(int pageWidthTwips, int pageHeightTwips, boolean landscape,
                          Integer marginTopTwips, Integer marginBottomTwips,
                          Integer marginLeftTwips, Integer marginRightTwips) {

        public float pageWidthPt() {
            return pageWidthTwips / 20f;
        }

        public float pageHeightPt() {
            return pageHeightTwips / 20f;
        }
    }
}
