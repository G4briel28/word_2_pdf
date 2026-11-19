package pe.saeta.word2pdf.support;

import java.net.URI;
import java.time.Duration;

import org.springframework.util.unit.DataSize;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.config.OnlyOfficeProperties;

/** Configuraciones construidas a mano para pruebas unitarias sin contexto Spring. */
public final class TestConfigs {

    /** Secreto SOLO para pruebas (no se usa en ningún entorno real). */
    public static final String TEST_SECRET = "test-only-secret-0123456789-abcdefghijklmnop";

    private TestConfigs() {
    }

    public static ConversionProperties conversion() {
        return conversion(4, Duration.ofSeconds(5));
    }

    public static ConversionProperties conversion(int maxConcurrent, Duration queueTimeout) {
        return new ConversionProperties(DataSize.ofMegabytes(20), DataSize.ofMegabytes(200), maxConcurrent, queueTimeout,
                new ConversionProperties.Docx(5000, DataSize.ofMegabytes(300), 200),
                new ConversionProperties.Fidelity(true, 2.0, false, false),
                new ConversionProperties.Compat(true));
    }

    public static ConversionProperties conversionWithDocxLimits(int maxEntries, DataSize maxUncompressed, int maxRatio) {
        return new ConversionProperties(DataSize.ofMegabytes(20), DataSize.ofMegabytes(200), 4, Duration.ofSeconds(5),
                new ConversionProperties.Docx(maxEntries, maxUncompressed, maxRatio),
                new ConversionProperties.Fidelity(true, 2.0, false, false),
                new ConversionProperties.Compat(true));
    }

    public static ConversionProperties conversionWithFidelity(boolean failOnPageSize, boolean failOnFonts) {
        return new ConversionProperties(DataSize.ofMegabytes(20), DataSize.ofMegabytes(200), 4, Duration.ofSeconds(5),
                new ConversionProperties.Docx(5000, DataSize.ofMegabytes(300), 200),
                new ConversionProperties.Fidelity(true, 2.0, failOnPageSize, failOnFonts),
                new ConversionProperties.Compat(true));
    }

    public static ConversionProperties conversionWithHyphenationCompat(boolean alignWithWord) {
        return new ConversionProperties(DataSize.ofMegabytes(20), DataSize.ofMegabytes(200), 4, Duration.ofSeconds(5),
                new ConversionProperties.Docx(5000, DataSize.ofMegabytes(300), 200),
                new ConversionProperties.Fidelity(true, 2.0, false, false),
                new ConversionProperties.Compat(alignWithWord));
    }

    public static OnlyOfficeProperties onlyOffice(String baseUrl, Duration conversionTimeout,
                                                  OnlyOfficeProperties.JwtTransport transport, boolean jwtEnabled) {
        return new OnlyOfficeProperties(URI.create(baseUrl),
                new OnlyOfficeProperties.Jwt(jwtEnabled, jwtEnabled ? TEST_SECRET : null, transport, Duration.ofMinutes(5)),
                Duration.ofSeconds(2), conversionTimeout, Duration.ofSeconds(2));
    }
}
