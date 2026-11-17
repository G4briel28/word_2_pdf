package pe.saeta.word2pdf.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Conexión con ONLYOFFICE Docs (Conversion API, endpoint {@code /converter/from-file}).
 *
 * @param baseUrl           URL interna del Document Server (red privada de Docker), p.ej. {@code http://onlyoffice}
 * @param jwt               configuración de firma JWT exigida por ONLYOFFICE
 * @param connectTimeout    timeout de conexión TCP
 * @param conversionTimeout tiempo máximo de espera de una conversión síncrona completa
 * @param healthTimeout     timeout del health check {@code GET /healthcheck}
 */
@Validated
@ConfigurationProperties("onlyoffice")
public record OnlyOfficeProperties(
        @NotNull URI baseUrl,
        @NotNull @Valid Jwt jwt,
        @NotNull Duration connectTimeout,
        @NotNull Duration conversionTimeout,
        @NotNull Duration healthTimeout) {

    /**
     * @param enabled   debe coincidir con {@code JWT_ENABLED} del contenedor (true en producción)
     * @param secret    debe coincidir con {@code JWT_SECRET} del contenedor; mínimo 32 bytes (HS256)
     * @param transport dónde se envía el token en la petición multipart
     * @param tokenTtl  vigencia del token firmado
     */
    public record Jwt(boolean enabled, String secret, @NotNull JwtTransport transport, @NotNull Duration tokenTtl) {

        @Override
        public String toString() {
            return "Jwt[enabled=" + enabled + ", secret=***, transport=" + transport + ", tokenTtl=" + tokenTtl + "]";
        }
    }

    public enum JwtTransport {
        /** Parte multipart {@code token} con los parámetros como claims de primer nivel (modo por defecto). */
        FORM_PART,
        /** Cabecera {@code Authorization: Bearer} con los parámetros envueltos en el claim {@code payload}. */
        HEADER
    }
}
