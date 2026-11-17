package pe.saeta.word2pdf.service;

/**
 * Puerto hacia el motor de conversión. Permite sustituir ONLYOFFICE por otro motor sin cambiar
 * la API REST ni {@link DocumentConversionService}.
 */
public interface ConversionEngine {

    /** Nombre corto del motor para métricas y logs, p.ej. {@code onlyoffice}. */
    String name();

    /**
     * Convierte un DOCX (ya validado, sin modificar) a PDF.
     *
     * @return bytes devueltos por el motor (se validan después como PDF)
     * @throws pe.saeta.word2pdf.exception.ConversionException con el código funcional correspondiente
     */
    byte[] convertDocxToPdf(byte[] docxBytes);
}
