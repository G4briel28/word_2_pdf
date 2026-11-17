package pe.saeta.word2pdf.client;

import java.util.Arrays;

import pe.saeta.word2pdf.exception.ErrorCode;

/**
 * Códigos de error de la Conversion API de ONLYOFFICE Docs
 * (https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/error-codes/).
 */
public enum OnlyOfficeErrorCode {

    UNKNOWN(-1, "Unknown error", ErrorCode.ENGINE_ERROR),
    TIMEOUT(-2, "Conversion timeout error", ErrorCode.CONVERSION_TIMEOUT),
    CONVERSION(-3, "Conversion error", ErrorCode.CONVERSION_FAILED),
    DOWNLOAD(-4, "Error while downloading the document file to be converted", ErrorCode.ENGINE_ERROR),
    PASSWORD(-5, "Incorrect password", ErrorCode.PASSWORD_PROTECTED),
    DATABASE(-6, "Error while accessing the conversion result database", ErrorCode.ENGINE_ERROR),
    INPUT(-7, "Input error", ErrorCode.ENGINE_ERROR),
    TOKEN(-8, "Invalid token", ErrorCode.ENGINE_AUTH_ERROR),
    FORMAT(-9, "Output format cannot be determined", ErrorCode.CONVERSION_FAILED),
    SIZE_LIMIT(-10, "Size limit exceeded", ErrorCode.DOCUMENT_TOO_LARGE);

    private final int code;
    private final String description;
    private final ErrorCode mappedTo;

    OnlyOfficeErrorCode(int code, String description, ErrorCode mappedTo) {
        this.code = code;
        this.description = description;
        this.mappedTo = mappedTo;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }

    public ErrorCode mappedTo() {
        return mappedTo;
    }

    public static OnlyOfficeErrorCode fromCode(int code) {
        return Arrays.stream(values()).filter(v -> v.code == code).findFirst().orElse(UNKNOWN);
    }
}
