package pe.saeta.word2pdf.exception;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import pe.saeta.word2pdf.config.RequestIdFilter;

/**
 * Traduce excepciones a respuestas JSON RFC 9457 (application/problem+json).
 * Nunca expone trazas, mensajes internos ni contenido del documento.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(ConversionException.class)
    public ResponseEntity<ProblemDetail> handleConversion(ConversionException ex) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            // Errores esperados del motor: sin traza completa, solo el tipo de la causa (sin datos del documento).
            String cause = ex.getCause() != null ? ex.getCause().getClass().getSimpleName() : "-";
            log.warn("Conversión fallida code={} reason={} cause={}", code, ex.getMessage(), cause);
        } else {
            log.info("Solicitud rechazada code={} reason={}", code, ex.getMessage());
        }
        return build(code, ex.detail());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUpload(MaxUploadSizeExceededException ex) {
        log.info("Solicitud rechazada code={} reason=multipart max size", ErrorCode.DOCUMENT_TOO_LARGE);
        return build(ErrorCode.DOCUMENT_TOO_LARGE, ErrorCode.DOCUMENT_TOO_LARGE.defaultMessage());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(ErrorCode.UNSUPPORTED_FORMAT,
                "Content-Type no soportado. Use application/octet-stream o multipart/form-data.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return build(ErrorCode.INVALID_REQUEST, "El servicio responde con application/pdf.");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MultipartException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception ex) {
        return build(ErrorCode.INVALID_REQUEST,
                "Solicitud inválida: falta la parte 'file' o el cuerpo multipart está mal formado.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail pd = problem(ErrorCode.INVALID_REQUEST, "Método HTTP no soportado.");
        pd.setStatus(405);
        return ResponseEntity.status(405).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException ex) {
        ProblemDetail pd = problem(ErrorCode.INVALID_REQUEST, "Recurso no encontrado.");
        pd.setStatus(404);
        return ResponseEntity.status(404).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Error inesperado type={}", ex.getClass().getName(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private ResponseEntity<ProblemDetail> build(ErrorCode code, String detail) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (code == ErrorCode.TOO_MANY_CONVERSIONS || code == ErrorCode.ENGINE_UNAVAILABLE) {
            builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return builder.body(problem(code, detail));
    }

    public static ProblemDetail problem(ErrorCode code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setType(URI.create("urn:word2pdf:error:" + code.slug()));
        pd.setTitle(code.status().getReasonPhrase());
        pd.setProperty("code", code.name());
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            pd.setProperty("requestId", requestId);
        }
        return pd;
    }
}
