package pe.saeta.word2pdf.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.exception.GlobalExceptionHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Autenticación por API key para uso en producción. Los health checks quedan exentos para que
 * Docker / el orquestador puedan sondearlos. La comparación se hace sobre digests SHA-256 en tiempo constante.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private final SecurityProperties properties;
    private final List<byte[]> keyDigests;
    private final ObjectMapper objectMapper;

    public ApiKeyAuthFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        if (properties.apiKeyEnabled() && properties.apiKeys().isEmpty()) {
            throw new IllegalStateException(
                    "app.security.api-key-enabled=true pero no hay claves configuradas (APP_API_KEYS)");
        }
        for (String key : properties.apiKeys()) {
            if (key.length() < 24) {
                throw new IllegalStateException("Cada API key debe tener al menos 24 caracteres");
            }
        }
        this.keyDigests = properties.apiKeys().stream().map(ApiKeyAuthFilter::sha256).toList();
        if (!properties.apiKeyEnabled()) {
            log.warn("Autenticación por API key DESACTIVADA. No usar esta configuración en producción.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.apiKeyEnabled()) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(properties.headerName());
        if (provided != null && isValid(provided)) {
            chain.doFilter(request, response);
            return;
        }
        log.info("Solicitud no autenticada rechazada path={}", request.getRequestURI());
        ProblemDetail pd = GlobalExceptionHandler.problem(ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
        response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), pd);
    }

    private boolean isValid(String provided) {
        byte[] digest = sha256(provided);
        boolean match = false;
        for (byte[] candidate : keyDigests) {
            // Sin cortocircuito: se comparan todas las claves.
            match |= MessageDigest.isEqual(candidate, digest);
        }
        return match;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
