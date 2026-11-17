package pe.saeta.word2pdf.exception;

import org.springframework.http.HttpStatus;

/**
 * Códigos de error funcionales expuestos por la API. Los mensajes son genéricos a propósito:
 * nunca incluyen contenido del documento, nombres de archivo ni detalles internos del motor.
 */
public enum ErrorCode {

    EMPTY_DOCUMENT(HttpStatus.BAD_REQUEST, "El documento está vacío."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "La solicitud no es válida."),
    INVALID_DOCX(HttpStatus.UNPROCESSABLE_CONTENT, "El archivo no es un documento DOCX válido o está dañado."),
    DOCUMENT_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "El documento supera el tamaño máximo permitido."),
    UNSUPPORTED_FORMAT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato no soportado. Solo se aceptan documentos DOCX."),
    PASSWORD_PROTECTED(HttpStatus.UNPROCESSABLE_CONTENT, "El documento está protegido con contraseña."),
    ENGINE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "El motor de conversión no está disponible."),
    ENGINE_AUTH_ERROR(HttpStatus.BAD_GATEWAY, "El motor de conversión rechazó la autenticación del servicio."),
    CONVERSION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "La conversión superó el tiempo máximo permitido."),
    CONVERSION_FAILED(HttpStatus.UNPROCESSABLE_CONTENT,
            "El motor no pudo convertir el documento: puede estar dañado o usar características no soportadas."),
    ENGINE_ERROR(HttpStatus.BAD_GATEWAY, "Error interno del motor de conversión."),
    INVALID_PDF_OUTPUT(HttpStatus.BAD_GATEWAY, "El motor de conversión devolvió un PDF inválido."),
    TOO_MANY_CONVERSIONS(HttpStatus.SERVICE_UNAVAILABLE, "Capacidad de conversión agotada. Reintente más tarde."),
    FIDELITY_CHECK_FAILED(HttpStatus.UNPROCESSABLE_CONTENT, "El PDF generado no superó los controles de fidelidad configurados."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Credenciales ausentes o inválidas."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servicio.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /** Identificador estable para clientes, p.ej. {@code invalid-docx}. */
    public String slug() {
        return name().toLowerCase().replace('_', '-');
    }
}
