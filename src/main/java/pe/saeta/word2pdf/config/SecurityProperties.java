package pe.saeta.word2pdf.config;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Autenticación de clientes de la API mediante API keys.
 *
 * @param apiKeyEnabled activa la verificación (debe estar activa en producción)
 * @param headerName    cabecera donde el cliente envía la clave
 * @param apiKeys       claves válidas (se recomienda inyectarlas por variable de entorno {@code APP_API_KEYS})
 */
@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(boolean apiKeyEnabled, @NotBlank String headerName, List<String> apiKeys) {

    public SecurityProperties {
        apiKeys = apiKeys == null ? List.of()
                : apiKeys.stream().filter(k -> k != null && !k.isBlank()).map(String::trim).toList();
    }

    @Override
    public String toString() {
        return "SecurityProperties[apiKeyEnabled=" + apiKeyEnabled + ", headerName=" + headerName
                + ", apiKeys=" + apiKeys.size() + " configuradas]";
    }
}
