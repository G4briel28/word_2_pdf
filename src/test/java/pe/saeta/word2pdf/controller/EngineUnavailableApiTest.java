package pe.saeta.word2pdf.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import pe.saeta.word2pdf.support.SampleDocxGenerator;

/** ONLYOFFICE caído: conversión 503 y readiness DOWN (liveness sigue UP para no reiniciar el servicio). */
@TestPropertySource(properties = {
        // Puerto 1 en loopback: conexión rechazada.
        "onlyoffice.base-url=http://127.0.0.1:1",
        "onlyoffice.connect-timeout=3s"
})
class EngineUnavailableApiTest extends ApiTestSupport {

    @Test
    void conversionFailsWith503AndReadinessIsDown() throws Exception {
        HttpResponse<byte[]> response = postBinary(SampleDocxGenerator.minimal());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(json(response.body()).get("code").asString()).isEqualTo("ENGINE_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();

        assertThat(get("/actuator/health/readiness", null).statusCode()).isEqualTo(503);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }
}
