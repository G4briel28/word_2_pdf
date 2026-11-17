package pe.saeta.word2pdf.controller;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import pe.saeta.word2pdf.support.FakeOnlyOfficeServer;
import pe.saeta.word2pdf.support.TestConfigs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base de pruebas de API: arranca la aplicación real en un puerto aleatorio apuntando a un ONLYOFFICE simulado.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "onlyoffice.base-url=${test.fake-onlyoffice-url}")
abstract class ApiTestSupport {

    static final String API_KEY = "test-api-key-0123456789-abcdefghij";
    static final FakeOnlyOfficeServer FAKE = new FakeOnlyOfficeServer(TestConfigs.TEST_SECRET);
    static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Las subclases pueden sobrescribir onlyoffice.base-url con @TestPropertySource.
        registry.add("test.fake-onlyoffice-url", FAKE::url);
        registry.add("onlyoffice.jwt.secret", () -> TestConfigs.TEST_SECRET);
        registry.add("app.security.api-keys", () -> API_KEY);
    }

    @BeforeEach
    void resetFake() {
        FAKE.reset();
    }

    URI uri(String pathAndQuery) {
        return URI.create("http://localhost:" + port + pathAndQuery);
    }

    HttpResponse<byte[]> postBinary(byte[] body) throws IOException, InterruptedException {
        return postBinary("/api/v1/conversion/docx-to-pdf", body, API_KEY);
    }

    HttpResponse<byte[]> postBinary(String path, byte[] body, String apiKey) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null) {
            b.header("X-API-Key", apiKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<byte[]> postMultipart(String filename, byte[] file) throws IOException, InterruptedException {
        String boundary = "test" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/conversion/docx-to-pdf/multipart"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-API-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(java.util.List.of(head, file, tail)))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    HttpResponse<String> get(String path, String apiKey) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET();
        if (apiKey != null) {
            b.header("X-API-Key", apiKey);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static JsonNode json(byte[] body) {
        return JSON.readTree(body);
    }
}
