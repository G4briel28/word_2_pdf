package pe.saeta.word2pdf.service;

import java.time.Duration;

import pe.saeta.word2pdf.fidelity.FidelityReport;

/**
 * @param pdf       bytes del PDF validado
 * @param pageCount páginas del PDF
 * @param fidelity  avisos de fidelidad (heurísticos)
 * @param duration  duración total (validación + conversión + verificación)
 */
public record ConversionResult(byte[] pdf, int pageCount, FidelityReport fidelity, Duration duration) {
}
