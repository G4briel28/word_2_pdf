package pe.saeta.word2pdf.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import com.nimbusds.jwt.JWTClaimsSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import pe.saeta.word2pdf.config.OnlyOfficeProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.support.FakeOnlyOfficeServer;
import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.support.TestConfigs;
import tools.jackson.databind.json.JsonMapper;

class OnlyOfficeClientTest {

    private static FakeOnlyOfficeServer fake;
    private static HttpClient httpClient;

    @BeforeAll
    static void start() {
        fake = new FakeOnlyOfficeServer(TestConfigs.TEST_SECRET);
        httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterAll
    static void stop() {
        fake.close();
        httpClient.close();
    }

    @BeforeEach
    void reset() {
        fake.reset();
    }

    private static OnlyOfficeClient client(String url, Duration timeout, OnlyOfficeProperties.JwtTransport transport,
                                           boolean jwt) {
        OnlyOfficeProperties props = TestConfigs.onlyOffice(url, timeout, transport, jwt);
        return new OnlyOfficeClient(httpClient, props, new OnlyOfficeJwtSigner(props), JsonMapper.builder().build(),
                TestConfigs.conversion());
    }

    private static OnlyOfficeClient client() {
        return client(fake.url(), Duration.ofSeconds(10), OnlyOfficeProperties.JwtTransport.FORM_PART, true);
    }

    @Test
    void sendsUnmodifiedDocxAndSignedTokenAndReturnsPdfBytes() {
        fake.setBehavior(FakeOnlyOfficeServer.Behavior.pdf(2));
        byte[] docx = SampleDocxGenerator.fullContract();

        byte[] pdf = client().convertDocxToPdf(docx);

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(fake.violations()).isEmpty();
        assertThat(fake.receivedFiles()).singleElement().satisfies(f -> assertThat(f).isEqualTo(docx));
        JWTClaimsSet claims = fake.receivedClaims().get(0);
        Map<String, Object> c = claims.getClaims();
        assertThat(c).containsEntry("operation", "converter").containsEntry("filetype", "docx")
                .containsEntry("outputtype", "pdf").containsEntry("title", "document.docx")
                .doesNotContainKey("url").containsKey("key");
    }

    @Test
    void headerTransportWrapsClaimsInPayload() {
        OnlyOfficeClient c = client(fake.url(), Duration.ofSeconds(10), OnlyOfficeProperties.JwtTransport.HEADER, true);
        assertThat(c.convertDocxToPdf(SampleDocxGenerator.minimal())).isNotEmpty();
        assertThat(fake.violations()).isEmpty();
        assertThat(fake.receivedClaims().get(0).getClaims()).containsKey("payload");
    }

    @Test
    void eachConversionUsesAUniqueKey() {
        OnlyOfficeClient c = client();
        c.convertDocxToPdf(SampleDocxGenerator.minimal());
        c.convertDocxToPdf(SampleDocxGenerator.minimal());
        assertThat(fake.receivedClaims()).extracting(cl -> cl.getClaims().get("key")).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @CsvSource({
            "-1, ENGINE_ERROR",
            "-2, CONVERSION_TIMEOUT",
            "-3, CONVERSION_FAILED",
            "-4, ENGINE_ERROR",
            "-5, PASSWORD_PROTECTED",
            "-6, ENGINE_ERROR",
            "-7, ENGINE_ERROR",
            "-8, ENGINE_AUTH_ERROR",
            "-9, CONVERSION_FAILED",
            "-10, DOCUMENT_TOO_LARGE",
            "-99, ENGINE_ERROR"
    })
    void mapsOnlyOfficeJsonErrors(int onlyOfficeCode, ErrorCode expected) {
        fake.setBehavior(FakeOnlyOfficeServer.Behavior.error(onlyOfficeCode));
        assertThatThrownBy(() -> client().convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }

    @Test
    void wrongSecretIsReportedAsEngineAuthError() {
        try (FakeOnlyOfficeServer other = new FakeOnlyOfficeServer("another-secret-that-does-not-match-at-all-xx")) {
            OnlyOfficeClient c = client(other.url(), Duration.ofSeconds(5), OnlyOfficeProperties.JwtTransport.FORM_PART, true);
            assertThatThrownBy(() -> c.convertDocxToPdf(SampleDocxGenerator.minimal()))
                    .isInstanceOfSatisfying(ConversionException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENGINE_AUTH_ERROR));
        }
    }

    @Test
    void missingTokenIsRejectedByServer() {
        OnlyOfficeClient c = client(fake.url(), Duration.ofSeconds(5), OnlyOfficeProperties.JwtTransport.FORM_PART, false);
        assertThatThrownBy(() -> c.convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENGINE_AUTH_ERROR));
    }

    @Test
    void gatewayErrorMeansEngineUnavailable() {
        fake.setBehavior(FakeOnlyOfficeServer.Behavior.httpStatus(502));
        assertThatThrownBy(() -> client().convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENGINE_UNAVAILABLE));
    }

    @Test
    void http413MeansDocumentTooLarge() {
        fake.setBehavior(FakeOnlyOfficeServer.Behavior.httpStatus(413));
        assertThatThrownBy(() -> client().convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @Test
    void slowEngineTimesOut() {
        fake.setBehavior(FakeOnlyOfficeServer.Behavior.pdf(1).delayed(3000));
        OnlyOfficeClient c = client(fake.url(), Duration.ofMillis(500), OnlyOfficeProperties.JwtTransport.FORM_PART, true);
        long start = System.nanoTime();
        assertThatThrownBy(() -> c.convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONVERSION_TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(2500));
    }

    @Test
    void unreachableEngineIsUnavailable() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        OnlyOfficeClient c = client("http://127.0.0.1:" + freePort, Duration.ofSeconds(2),
                OnlyOfficeProperties.JwtTransport.FORM_PART, true);
        assertThatThrownBy(() -> c.convertDocxToPdf(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ENGINE_UNAVAILABLE));
    }
}
