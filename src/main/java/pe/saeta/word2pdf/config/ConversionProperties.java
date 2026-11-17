package pe.saeta.word2pdf.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Límites del servicio de conversión.
 *
 * @param maxInputSize  tamaño máximo del DOCX recibido
 * @param maxOutputSize tamaño máximo aceptado para el PDF devuelto por el motor
 * @param maxConcurrent conversiones simultáneas permitidas hacia el motor
 * @param queueTimeout  espera máxima por un cupo de conversión antes de responder 503
 * @param docx          límites anti zip-bomb para el contenedor OOXML
 * @param fidelity      controles de fidelidad post-conversión
 */
@Validated
@ConfigurationProperties("app.conversion")
public record ConversionProperties(
        @NotNull DataSize maxInputSize,
        @NotNull DataSize maxOutputSize,
        @Min(1) int maxConcurrent,
        @NotNull Duration queueTimeout,
        @NotNull @Valid Docx docx,
        @NotNull @Valid Fidelity fidelity) {

    /**
     * @param maxEntries          número máximo de entradas en el ZIP
     * @param maxUncompressedSize suma máxima de bytes descomprimidos (contados realmente, no según la cabecera)
     * @param maxCompressionRatio ratio máximo descomprimido/comprimido por entrada grande
     */
    public record Docx(@Positive int maxEntries, @NotNull DataSize maxUncompressedSize, @Positive int maxCompressionRatio) {
    }

    /**
     * @param enabled                  ejecuta los controles de fidelidad (tamaño de página y fuentes)
     * @param pageSizeTolerancePoints  tolerancia en puntos (1/72") al comparar tamaños de página
     * @param failOnPageSizeMismatch   si es true, un tamaño de página distinto al del DOCX produce error 422
     * @param failOnFontSubstitution   si es true, una sustitución de fuente sospechada produce error 422
     */
    public record Fidelity(boolean enabled, @Positive double pageSizeTolerancePoints,
                           boolean failOnPageSizeMismatch, boolean failOnFontSubstitution) {
    }
}
