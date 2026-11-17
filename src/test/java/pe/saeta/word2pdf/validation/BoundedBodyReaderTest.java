package pe.saeta.word2pdf.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Random;

import org.junit.jupiter.api.Test;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;

class BoundedBodyReaderTest {

    @Test
    void readsBodyWithAndWithoutContentLength() throws IOException {
        byte[] data = new byte[300_000];
        new Random(1).nextBytes(data);
        assertThat(BoundedBodyReader.read(new ByteArrayInputStream(data), data.length, 1_000_000)).isEqualTo(data);
        assertThat(BoundedBodyReader.read(new ByteArrayInputStream(data), -1, 1_000_000)).isEqualTo(data);
    }

    @Test
    void acceptsExactlyMaxBytes() throws IOException {
        byte[] data = new byte[2048];
        assertThat(BoundedBodyReader.read(new ByteArrayInputStream(data), -1, 2048)).hasSize(2048);
    }

    @Test
    void rejectsDeclaredLengthOverMax() {
        assertThatThrownBy(() -> BoundedBodyReader.read(new ByteArrayInputStream(new byte[10]), 5000, 1000))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @Test
    void rejectsLyingContentLength() {
        // Content-Length dice 10 pero llegan 5000 bytes: se corta al superar el máximo.
        assertThatThrownBy(() -> BoundedBodyReader.read(new ByteArrayInputStream(new byte[5000]), 10, 1000))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @Test
    void returnsEmptyArrayForEmptyBody() throws IOException {
        assertThat(BoundedBodyReader.read(new ByteArrayInputStream(new byte[0]), 0, 1000)).isEmpty();
    }
}
