package pe.saeta.word2pdf.client;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.OnlyOfficeProperties;

/**
 * Firma HS256 de los tokens que exige ONLYOFFICE Docs. El secreto se obtiene de configuración
 * externa (variable de entorno {@code ONLYOFFICE_JWT_SECRET}); nunca está en el código.
 */
@Component
public class OnlyOfficeJwtSigner {

    static final int MIN_SECRET_BYTES = 32;

    private final OnlyOfficeProperties.Jwt config;
    private final MACSigner signer;
    private final Clock clock;

    @Autowired
    public OnlyOfficeJwtSigner(OnlyOfficeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    OnlyOfficeJwtSigner(OnlyOfficeProperties properties, Clock clock) {
        this.config = properties.jwt();
        this.clock = clock;
        if (!config.enabled()) {
            this.signer = null;
            return;
        }
        String secret = config.secret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("onlyoffice.jwt.secret (ONLYOFFICE_JWT_SECRET) es obligatorio y debe tener "
                    + "al menos " + MIN_SECRET_BYTES + " bytes cuando onlyoffice.jwt.enabled=true");
        }
        try {
            this.signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
        } catch (JOSEException e) {
            throw new IllegalStateException("Secreto JWT no válido para HS256", e);
        }
    }

    public boolean enabled() {
        return config.enabled();
    }

    /**
     * Firma los claims indicados añadiendo {@code iat} y {@code exp}.
     */
    public String sign(Map<String, Object> claims) {
        if (signer == null) {
            throw new IllegalStateException("JWT deshabilitado");
        }
        Instant now = clock.instant();
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(config.tokenTtl())));
        claims.forEach(builder::claim);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), builder.build());
        try {
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token JWT", e);
        }
        return jwt.serialize();
    }
}
