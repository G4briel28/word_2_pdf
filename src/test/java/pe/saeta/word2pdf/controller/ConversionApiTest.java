package pe.saeta.word2pdf.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import pe.saeta.word2pdf.fidelity.FidelityReport;
import pe.saeta.word2pdf.service.ConversionLimiter;
import pe.saeta.word2pdf.support.FakeOnlyOfficeServer.Behavior;
import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.support.TestPdfs;
import tools.jackson.databind.JsonNode;

/**
 * Pruebas de extremo a extremo de la API con ONLYOFFICE simulado: cubren el flujo byte[] → PDF,
 * documentos de varias páginas, tablas/encabezados, documentos corruptos, errores del motor, timeouts,
 * validación del PDF y conversiones consecutivas.
 */
@TestPropertySource(properties = {
        "APP_MAX_INPUT_SIZE=3MB",
        "onlyoffice.conversion-timeout=1500ms"
})
class ConversionApiTest extends ApiTestSupport {

    @Autowired
    ConversionLimiter limiter;

    @Test
    void convertsBinaryDocxToPdf() throws Exception {
        FAKE.setBehavior(Behavior.pdf(2));
        byte[] docx = SampleDocxGenerator.minimal();

        HttpResponse<byte[]> response = postBinary("/api/v1/conversion/docx-to-pdf?filename=Contrato%20Demo.docx",
                docx, API_KEY);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition")).get().asString()
                .contains("attachment").contains("Contrato Demo.pdf");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control")).get().asString().contains("no-store");
        assertThat(response.headers().firstValue(ConversionController.HEADER_PAGE_COUNT)).hasValue("2");
        try (PDDocument pdf = Loader.loadPDF(response.body())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(FAKE.receivedFiles()).singleElement().satisfies(f -> assertThat(f).isEqualTo(docx));
        assertThat(FAKE.violations()).isEmpty();
    }

    @Test
    void convertsMultipartUpload() throws Exception {
        FAKE.setBehavior(Behavior.pdf(1));
        HttpResponse<byte[]> response = postMultipart("acuerdo-prueba.docx", SampleDocxGenerator.minimal());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(new String(response.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(response.headers().firstValue("Content-Disposition")).get().asString().contains("acuerdo-prueba.pdf");
    }

    @Test
    void convertsMultiPageContractWithTablesAndHeaders() throws Exception {
        FAKE.setBehavior(Behavior.pdf(5));
        HttpResponse<byte[]> response = postBinary(SampleDocxGenerator.fullContract());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue(ConversionController.HEADER_PAGE_COUNT)).hasValue("5");
        // El simulador devuelve Helvetica: el servicio debe avisar de la posible sustitución de fuentes.
        assertThat(response.headers().firstValue(ConversionController.HEADER_WARNINGS)).get().asString()
                .contains(FidelityReport.FONT_SUBSTITUTION_SUSPECTED);
        assertThat(response.headers().firstValue(ConversionController.HEADER_SUSPECTED_FONTS)).get().asString()
                .contains("Arial").contains("Times New Roman");
    }

    @Test
    void inspectEndpointReportsSectionsAndFonts() throws Exception {
        HttpResponse<byte[]> response = postBinary("/api/v1/conversion/docx/inspect", SampleDocxGenerator.fullContract(), API_KEY);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response.body());
        assertThat(body.get("sections")).hasSize(2);
        assertThat(body.get("fonts").toString()).contains("Arial", "Times New Roman");
        assertThat(FAKE.requests()).isZero();
    }

    @Test
    void corruptDocumentIsRejectedWithoutCallingEngine() throws Exception {
        byte[] docx = SampleDocxGenerator.fullContract();
        byte[] truncated = java.util.Arrays.copyOf(docx, docx.length - 100);
        HttpResponse<byte[]> response = postBinary(truncated);

        assertProblem(response, 422, "INVALID_DOCX");
        assertThat(FAKE.requests()).isZero();
    }

    @Test
    void emptyBodyIsRejected() throws Exception {
        assertProblem(postBinary(new byte[0]), 400, "EMPTY_DOCUMENT");
    }

    @Test
    void unsupportedFormatIsRejected() throws Exception {
        assertProblem(postBinary(TestPdfs.a4(1)), 415, "UNSUPPORTED_FORMAT");
        byte[] ole = new byte[1024];
        System.arraycopy(new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
                0, ole, 0, 8);
        assertProblem(postBinary(ole), 415, "UNSUPPORTED_FORMAT");
    }

    @Test
    void wrongContentTypeIsRejected() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/conversion/docx-to-pdf"))
                .header("Content-Type", "text/plain").header("X-API-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString("hola")).build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertProblem(response, 415, "UNSUPPORTED_FORMAT");
    }

    @Test
    void tooLargeDocumentIsRejected() throws Exception {
        assertProblem(postBinary(new byte[4 * 1024 * 1024]), 413, "DOCUMENT_TOO_LARGE");
        assertThat(FAKE.requests()).isZero();
    }

    @Test
    void engineConversionErrorIsMapped() throws Exception {
        FAKE.setBehavior(Behavior.error(-3));
        assertProblem(postBinary(SampleDocxGenerator.minimal()), 422, "CONVERSION_FAILED");
    }

    @Test
    void engineAuthErrorIsMapped() throws Exception {
        FAKE.setBehavior(Behavior.error(-8));
        assertProblem(postBinary(SampleDocxGenerator.minimal()), 502, "ENGINE_AUTH_ERROR");
    }

    @Test
    void engineGatewayErrorIsUnavailableWithRetryAfter() throws Exception {
        FAKE.setBehavior(Behavior.httpStatus(502));
        HttpResponse<byte[]> response = postBinary(SampleDocxGenerator.minimal());
        assertProblem(response, 503, "ENGINE_UNAVAILABLE");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void slowConversionTimesOut() throws Exception {
        FAKE.setBehavior(Behavior.pdf(1).delayed(4000));
        assertProblem(postBinary(SampleDocxGenerator.minimal()), 504, "CONVERSION_TIMEOUT");
    }

    @Test
    void invalidPdfFromEngineIsRejected() throws Exception {
        FAKE.setBehavior(Behavior.notPdf());
        assertProblem(postBinary(SampleDocxGenerator.minimal()), 502, "INVALID_PDF_OUTPUT");
    }

    @Test
    void consecutiveConversionsDoNotDegrade() throws Exception {
        FAKE.setBehavior(Behavior.pdf(3));
        byte[] docx = SampleDocxGenerator.fullContract();
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long start = System.nanoTime();
            HttpResponse<byte[]> response = postBinary(docx);
            millis.add(Duration.ofNanos(System.nanoTime() - start).toMillis());
            assertThat(response.statusCode()).isEqualTo(200);
            try (PDDocument pdf = Loader.loadPDF(response.body())) {
                assertThat(pdf.getNumberOfPages()).isEqualTo(3);
            }
        }
        assertThat(FAKE.requests()).isEqualTo(25);
        assertThat(limiter.inFlight()).isZero();
        assertThat(limiter.availablePermits()).isEqualTo(4);
        // Sin degradación progresiva: las últimas 5 no deben ser drásticamente más lentas que las 5 primeras tras el calentamiento.
        double early = millis.subList(1, 6).stream().mapToLong(Long::longValue).average().orElse(1);
        double late = millis.subList(20, 25).stream().mapToLong(Long::longValue).average().orElse(1);
        assertThat(late).isLessThan(Math.max(early * 5, 250));
    }

    @Test
    void errorResponsesDoNotLeakInternals() throws Exception {
        FAKE.setBehavior(Behavior.error(-8));
        HttpResponse<byte[]> response = postBinary(SampleDocxGenerator.minimal());
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Exception").doesNotContain("at pe.saeta").doesNotContain("secret")
                .doesNotContain("key=");
    }

    @Test
    void apiKeyIsRequired() throws Exception {
        assertProblem(postBinary("/api/v1/conversion/docx-to-pdf", SampleDocxGenerator.minimal(), null), 401, "UNAUTHORIZED");
        assertProblem(postBinary("/api/v1/conversion/docx-to-pdf", SampleDocxGenerator.minimal(), "clave-incorrecta-123456789012"),
                401, "UNAUTHORIZED");
        assertThat(get("/actuator/metrics", null).statusCode()).isEqualTo(401);
        assertThat(FAKE.requests()).isZero();
    }

    @Test
    void healthEndpointsArePublicAndReadinessChecksOnlyOffice() throws Exception {
        HttpResponse<String> health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("UP");
        HttpResponse<String> readiness = get("/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
    }

    @Test
    void conversionMetricsAreExposed() throws Exception {
        FAKE.setBehavior(Behavior.pdf(1));
        postBinary(SampleDocxGenerator.minimal());
        HttpResponse<String> metrics = get("/actuator/metrics/docconv.conversion.duration", API_KEY);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("COUNT");
    }

    private static void assertProblem(HttpResponse<byte[]> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).get().asString().contains("application/problem+json");
        JsonNode body = json(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.has("detail")).isTrue();
        assertThat(body.has("trace")).isFalse();
    }
}
