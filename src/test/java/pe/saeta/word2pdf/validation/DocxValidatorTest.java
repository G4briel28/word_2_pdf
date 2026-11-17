package pe.saeta.word2pdf.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.support.TestConfigs;
import pe.saeta.word2pdf.support.TestPdfs;

class DocxValidatorTest {

    private final DocxValidator validator = new DocxValidator(TestConfigs.conversion());

    @Test
    void acceptsGeneratedContract() {
        assertThatCode(() -> validator.validate(SampleDocxGenerator.fullContract())).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(SampleDocxGenerator.minimal())).doesNotThrowAnyException();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertCode(null, ErrorCode.EMPTY_DOCUMENT);
        assertCode(new byte[0], ErrorCode.EMPTY_DOCUMENT);
    }

    @Test
    void rejectsTooLarge() {
        DocxValidator small = new DocxValidator(new pe.saeta.word2pdf.config.ConversionProperties(
                DataSize.ofBytes(100), DataSize.ofMegabytes(1), 1, java.time.Duration.ofSeconds(1),
                new pe.saeta.word2pdf.config.ConversionProperties.Docx(10, DataSize.ofMegabytes(1), 100),
                new pe.saeta.word2pdf.config.ConversionProperties.Fidelity(false, 2, false, false)));
        assertThatThrownBy(() -> small.validate(SampleDocxGenerator.minimal()))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DOCUMENT_TOO_LARGE));
    }

    @Test
    void rejectsLegacyDocOrEncryptedDocx() {
        byte[] ole = new byte[512];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, ole, 0, magic.length);
        assertCode(ole, ErrorCode.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsNonZipFormats() {
        assertCode(TestPdfs.a4(1), ErrorCode.UNSUPPORTED_FORMAT);
        assertCode("{\\rtf1 hola}".getBytes(StandardCharsets.US_ASCII), ErrorCode.UNSUPPORTED_FORMAT);
        assertCode("texto plano".getBytes(StandardCharsets.UTF_8), ErrorCode.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsTruncatedZip() {
        byte[] docx = SampleDocxGenerator.fullContract();
        assertCode(Arrays.copyOf(docx, docx.length / 2), ErrorCode.INVALID_DOCX);
    }

    @Test
    void rejectsCorruptedZipData() {
        byte[] docx = SampleDocxGenerator.fullContract();
        // Corrompe datos comprimidos en mitad del archivo manteniendo cabecera y directorio central.
        for (int i = 200; i < 600; i++) {
            docx[i] = (byte) (docx[i] ^ 0x5A);
        }
        assertCode(docx, ErrorCode.INVALID_DOCX);
    }

    @Test
    void rejectsZipWithoutContentTypes() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        assertCode(SampleDocxGenerator.zip(entries), ErrorCode.INVALID_DOCX);
    }

    @Test
    void rejectsZipWhoseMainPartIsMissing() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(DocxValidator.DOCX_MAIN));
        assertCode(SampleDocxGenerator.zip(entries), ErrorCode.INVALID_DOCX);
    }

    @Test
    void rejectsMacroEnabledDocument() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes("application/vnd.ms-word.document.macroEnabled.main+xml"));
        entries.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        assertCode(SampleDocxGenerator.zip(entries), ErrorCode.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsSpreadsheet() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml",
                contentTypes("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"));
        entries.put("word/document.xml", "<x/>".getBytes(StandardCharsets.UTF_8));
        assertCode(SampleDocxGenerator.zip(entries), ErrorCode.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsXxeInContentTypes() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", ("<?xml version=\"1.0\"?><!DOCTYPE t [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">&x;</Types>")
                .getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        assertCode(SampleDocxGenerator.zip(entries), ErrorCode.INVALID_DOCX);
    }

    @Test
    void rejectsZipBombByTotalSize() {
        DocxValidator strict = new DocxValidator(TestConfigs.conversionWithDocxLimits(5000, DataSize.ofMegabytes(5), 10_000));
        assertThatThrownBy(() -> strict.validate(bomb(8 * 1024 * 1024)))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_DOCX));
    }

    @Test
    void rejectsZipBombByCompressionRatio() {
        DocxValidator strict = new DocxValidator(TestConfigs.conversionWithDocxLimits(5000, DataSize.ofMegabytes(300), 100));
        assertThatThrownBy(() -> strict.validate(bomb(4 * 1024 * 1024)))
                .isInstanceOfSatisfying(ConversionException.class,
                        e -> assertThat(e.getMessage()).contains("Ratio"));
    }

    @Test
    void rejectsTooManyEntries() {
        DocxValidator strict = new DocxValidator(TestConfigs.conversionWithDocxLimits(5, DataSize.ofMegabytes(300), 200));
        assertCodeWith(strict, SampleDocxGenerator.fullContract(), ErrorCode.INVALID_DOCX);
    }

    @Test
    void rejectsPathTraversalEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(DocxValidator.DOCX_MAIN));
        entries.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        entries.put("../../evil.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertCode(SampleDocxGenerator.zip(entries), ErrorCode.INVALID_DOCX);
    }

    private static byte[] bomb(int zeros) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", contentTypes(DocxValidator.DOCX_MAIN));
        entries.put("word/document.xml", new byte[zeros]);
        return SampleDocxGenerator.zip(entries);
    }

    private static byte[] contentTypes(String mainType) {
        return ("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"" + mainType + "\"/></Types>")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void assertCode(byte[] input, ErrorCode expected) {
        assertCodeWith(validator, input, expected);
    }

    private static void assertCodeWith(DocxValidator v, byte[] input, ErrorCode expected) {
        assertThatThrownBy(() -> v.validate(input))
                .isInstanceOfSatisfying(ConversionException.class, e -> assertThat(e.errorCode()).isEqualTo(expected));
    }
}
