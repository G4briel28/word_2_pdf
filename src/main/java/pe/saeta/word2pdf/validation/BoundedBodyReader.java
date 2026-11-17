package pe.saeta.word2pdf.validation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;

/**
 * Lee el cuerpo de una petición sin superar nunca el máximo permitido en memoria
 * (no confía en {@code Content-Length}, que puede faltar o mentir).
 */
public final class BoundedBodyReader {

    private BoundedBodyReader() {
    }

    public static byte[] read(InputStream in, long declaredLength, long maxBytes) throws IOException {
        if (declaredLength > maxBytes) {
            throw new ConversionException(ErrorCode.DOCUMENT_TOO_LARGE, ErrorCode.DOCUMENT_TOO_LARGE.defaultMessage(),
                    "Content-Length declarado supera el máximo");
        }
        int initial = (int) Math.min(declaredLength > 0 ? declaredLength : 64 * 1024, maxBytes);
        byte[] buffer = new byte[(int) Math.max(1, Math.min(Math.max(initial, 1024), maxBytes))];
        int total = 0;
        while (true) {
            if (total == buffer.length) {
                if (total >= maxBytes) {
                    // Buffer lleno al máximo: si queda al menos un byte más, se rechaza.
                    if (in.read() != -1) {
                        throw new ConversionException(ErrorCode.DOCUMENT_TOO_LARGE,
                                ErrorCode.DOCUMENT_TOO_LARGE.defaultMessage(), "Cuerpo supera el máximo");
                    }
                    break;
                }
                buffer = Arrays.copyOf(buffer, (int) Math.min((long) buffer.length * 2, maxBytes));
            }
            int n = in.read(buffer, total, buffer.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total == buffer.length ? buffer : Arrays.copyOf(buffer, total);
    }
}
