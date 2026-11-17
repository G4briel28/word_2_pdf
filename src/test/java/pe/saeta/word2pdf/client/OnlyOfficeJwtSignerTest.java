package pe.saeta.word2pdf.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.Test;

import pe.saeta.word2pdf.config.OnlyOfficeProperties;
import pe.saeta.word2pdf.support.TestConfigs;

class OnlyOfficeJwtSignerTest {

    @Test
    void signsHs256WithIatAndExp() throws Exception {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");
        OnlyOfficeJwtSigner signer = new OnlyOfficeJwtSigner(
                TestConfigs.onlyOffice("http://x", Duration.ofSeconds(5), OnlyOfficeProperties.JwtTransport.FORM_PART, true),
                Clock.fixed(now, ZoneOffset.UTC));

        String token = signer.sign(Map.of("operation", "converter", "outputtype", "pdf"));
        SignedJWT jwt = SignedJWT.parse(token);

        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(TestConfigs.TEST_SECRET.getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getStringClaim("operation")).isEqualTo("converter");
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(now);
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(now.plus(Duration.ofMinutes(5)));
    }

    @Test
    void rejectsShortOrMissingSecret() {
        for (String secret : new String[] {null, "", "corto"}) {
            OnlyOfficeProperties props = new OnlyOfficeProperties(URI.create("http://x"),
                    new OnlyOfficeProperties.Jwt(true, secret, OnlyOfficeProperties.JwtTransport.FORM_PART, Duration.ofMinutes(5)),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
            assertThatThrownBy(() -> new OnlyOfficeJwtSigner(props)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void secretIsNotPrintedInToString() {
        OnlyOfficeProperties props = TestConfigs.onlyOffice("http://x", Duration.ofSeconds(5),
                OnlyOfficeProperties.JwtTransport.FORM_PART, true);
        assertThat(props.toString()).doesNotContain(TestConfigs.TEST_SECRET);
    }

    @Test
    void disabledSignerCannotSign() {
        OnlyOfficeJwtSigner signer = new OnlyOfficeJwtSigner(
                TestConfigs.onlyOffice("http://x", Duration.ofSeconds(5), OnlyOfficeProperties.JwtTransport.FORM_PART, false));
        assertThat(signer.enabled()).isFalse();
        assertThatThrownBy(() -> signer.sign(Map.of())).isInstanceOf(IllegalStateException.class);
    }
}
