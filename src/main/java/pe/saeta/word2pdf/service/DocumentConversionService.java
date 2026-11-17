package pe.saeta.word2pdf.service;

/**
 * Contrato de conversión independiente del motor utilizado.
 */
public interface DocumentConversionService {

    /**
     * Convierte un DOCX a PDF.
     *
     * @param docxBytes bytes del documento Word (DOCX)
     * @return bytes del PDF generado y validado
     * @throws pe.saeta.word2pdf.exception.ConversionException si el documento no es válido o la conversión falla
     */
    byte[] convertDocxToPdf(byte[] docxBytes);

    /**
     * Igual que {@link #convertDocxToPdf(byte[])} pero devuelve también metadatos y avisos de fidelidad.
     */
    ConversionResult convert(byte[] docxBytes);
}
