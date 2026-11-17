package pe.saeta.word2pdf.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.fidelity.FidelityReport;
import pe.saeta.word2pdf.service.ConversionResult;
import pe.saeta.word2pdf.service.DocumentConversionService;
import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.validation.PdfInfo;
import pe.saeta.word2pdf.validation.PdfValidator;

/**
 * Integración REAL con ONLYOFFICE Docs (imagen Docker {@code onlyoffice/documentserver:10.0.0}).
 * Se ejecuta con {@code ./mvnw -Pintegration verify}; se omite automáticamente si no hay Docker.
 * Los PDF generados se guardan en {@code target/it-output/} para revisión visual manual.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "app.security.api-keys=integration-test-api-key-0123456789",
        "onlyoffice.conversion-timeout=180s",
        "app.conversion.max-concurrent=3"
})
class RealOnlyOfficeConversionIT {

    private static final String SECRET = "it-" + UUID.randomUUID() + UUID.randomUUID();
    private static final Path OUTPUT = Path.of("target", "it-output");

    @Container
    static final GenericContainer<?> ONLYOFFICE = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("onlyoffice.image", "onlyoffice/documentserver:10.0.0")))
            .withEnv("JWT_ENABLED", "true")
            .withEnv("JWT_SECRET", SECRET)
            .withExposedPorts(80)
            .waitingFor(Wait.forHttp("/healthcheck").forStatusCode(200)
                    .forResponsePredicate(body -> body.trim().equals("true"))
                    .withStartupTimeout(Duration.ofMinutes(8)));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("onlyoffice.base-url", () -> "http://" + ONLYOFFICE.getHost() + ":" + ONLYOFFICE.getMappedPort(80));
        registry.add("onlyoffice.jwt.secret", () -> SECRET);
    }

    @Autowired
    DocumentConversionService service;

    @Autowired
    PdfValidator pdfValidator;

    @BeforeAll
    static void prepareOutput() throws Exception {
        Files.createDirectories(OUTPUT);
    }

    @Test
    void convertsSimpleDocument() throws Exception {
        byte[] pdf = service.convertDocxToPdf(SampleDocxGenerator.minimal());
        PdfInfo info = pdfValidator.validate(pdf);
        assertThat(info.pageCount()).isGreaterThanOrEqualTo(1);
        Files.write(OUTPUT.resolve("contrato-simple.pdf"), pdf);
    }

    @Test
    void convertsMultiPageContractPreservingPageSizes() throws Exception {
        ConversionResult result = service.convert(SampleDocxGenerator.fullContract());
        Files.write(OUTPUT.resolve("contrato-ejemplo.pdf"), result.pdf());

        PdfInfo info = pdfValidator.validate(result.pdf());
        assertThat(info.pageCount()).isGreaterThanOrEqualTo(4);
        // Primera página A4 vertical; última (anexo) Carta horizontal.
        assertThat(info.pages().get(0).widthPt()).isBetween(593f, 597f);
        assertThat(info.pages().get(0).heightPt()).isBetween(840f, 844f);
        PdfInfo.PageSize last = info.pages().get(info.pageCount() - 1);
        assertThat(last.widthPt()).isBetween(790f, 794f);
        assertThat(last.heightPt()).isBetween(610f, 614f);
        assertThat(result.fidelity().warnings()).doesNotContain(FidelityReport.PAGE_SIZE_MISMATCH);
        System.out.println("Avisos de fidelidad (informativo): " + result.fidelity());
    }

    @Test
    void consecutiveConversions() {
        byte[] docx = SampleDocxGenerator.fullContract();
        for (int i = 0; i < 5; i++) {
            assertThat(pdfValidator.validate(service.convertDocxToPdf(docx)).pageCount()).isGreaterThan(0);
        }
    }

    @Test
    void parallelConversions() throws Exception {
        byte[] docx = SampleDocxGenerator.fullContract();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> service.convertDocxToPdf(docx)));
            }
            for (Future<byte[]> f : futures) {
                assertThat(pdfValidator.validate(f.get(5, TimeUnit.MINUTES)).pageCount()).isGreaterThan(0);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void brokenDocumentXmlIsReportedAsError() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", ("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"" + SampleDocxGenerator.CT_DOCX_MAIN + "\"/></Types>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("_rels/.rels", ("<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>").getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", "<<<esto no es xml".getBytes(StandardCharsets.UTF_8));
        byte[] broken = SampleDocxGenerator.zip(entries);

        // ONLYOFFICE puede devolver error o un PDF vacío/mínimo; ambos casos deben ser controlados.
        try {
            byte[] pdf = service.convertDocxToPdf(broken);
            assertThat(pdfValidator.validate(pdf).pageCount()).isGreaterThan(0);
        } catch (ConversionException e) {
            assertThat(e.errorCode().status().value()).isIn(422, 502);
        }
    }

    @Test
    void wrongSecretIsRejectedByOnlyOffice() {
        // Verificación de que ONLYOFFICE valida el JWT: un token firmado con otro secreto debe fallar (-8).
        assertThatThrownBy(() -> {
            var props = pe.saeta.word2pdf.support.TestConfigs.onlyOffice(
                    "http://" + ONLYOFFICE.getHost() + ":" + ONLYOFFICE.getMappedPort(80), Duration.ofSeconds(60),
                    pe.saeta.word2pdf.config.OnlyOfficeProperties.JwtTransport.FORM_PART, true);
            var client = new pe.saeta.word2pdf.client.OnlyOfficeClient(
                    java.net.http.HttpClient.newHttpClient(), props,
                    new pe.saeta.word2pdf.client.OnlyOfficeJwtSigner(props),
                    tools.jackson.databind.json.JsonMapper.builder().build(),
                    pe.saeta.word2pdf.support.TestConfigs.conversion());
            client.convertDocxToPdf(SampleDocxGenerator.minimal());
        }).isInstanceOfSatisfying(ConversionException.class,
                e -> assertThat(e.errorCode().name()).isEqualTo("ENGINE_AUTH_ERROR"));
    }
}
