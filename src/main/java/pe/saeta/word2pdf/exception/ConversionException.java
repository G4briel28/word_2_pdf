package pe.saeta.word2pdf.exception;

/**
 * Excepción funcional del flujo de conversión.
 * <p>
 * {@code detail} es un texto seguro para devolver al cliente (sin contenido del documento).
 * {@code internalReason} solo se registra en logs y tampoco debe contener datos del documento.
 */
public class ConversionException extends RuntimeException {

    private final ErrorCode errorCode;
    private final String detail;

    public ConversionException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    public ConversionException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, null, null);
    }

    public ConversionException(ErrorCode errorCode, String detail, String internalReason) {
        this(errorCode, detail, internalReason, null);
    }

    public ConversionException(ErrorCode errorCode, String detail, String internalReason, Throwable cause) {
        super(errorCode.name() + (internalReason != null ? ": " + internalReason : ""), cause);
        this.errorCode = errorCode;
        this.detail = detail != null ? detail : errorCode.defaultMessage();
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public String detail() {
        return detail;
    }
}
