package pe.saeta.word2pdf.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.OnlyOfficeProperties;

/**
 * Health check de ONLYOFFICE Docs: {@code GET /healthcheck} responde {@code true} cuando el servicio está operativo.
 * Se incluye en el grupo de readiness (no en liveness) para no reiniciar este servicio si el motor cae.
 */
@Component("onlyoffice")
public class OnlyOfficeHealthIndicator extends AbstractHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OnlyOfficeHealthIndicator.class);

    private final HttpClient httpClient;
    private final OnlyOfficeProperties properties;
    private final URI healthUri;

    public OnlyOfficeHealthIndicator(HttpClient httpClient, OnlyOfficeProperties properties) {
        super("Health check de ONLYOFFICE fallido");
        this.httpClient = httpClient;
        this.properties = properties;
        this.healthUri = properties.baseUrl().resolve("/healthcheck");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        HttpRequest request = HttpRequest.newBuilder(healthUri).timeout(properties.healthTimeout()).GET().build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean healthy = response.statusCode() == 200 && "true".equalsIgnoreCase(response.body().trim());
            (healthy ? builder.up() : builder.down()).withDetail("status", response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            builder.down().withDetail("error", "interrupted");
        } catch (IOException e) {
            // Motor caído: DOWN sin volcar la traza en cada sondeo de readiness.
            if (log.isDebugEnabled()) {
                log.debug("ONLYOFFICE no responde al health check: {}", e.getClass().getSimpleName());
            }
            builder.down().withDetail("error", e.getClass().getSimpleName());
        }
    }
}
