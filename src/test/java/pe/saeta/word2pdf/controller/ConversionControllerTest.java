package pe.saeta.word2pdf.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ConversionControllerTest {

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "NULL, documento.pdf",
            "'', documento.pdf",
            "contrato.docx, contrato.pdf",
            "C:\\Users\\x\\Contrato Final.docx, Contrato Final.pdf",
            "../../etc/passwd, passwd.pdf",
            "Contrato Pérez.docx, Contrato P_rez.pdf",
            "\"; rm -rf.docx, __ rm -rf.pdf",
            "....docx, documento.pdf"
    })
    void sanitizesPdfFileName(String requested, String expected) {
        assertThat(ConversionController.pdfFileName(requested)).isEqualTo(expected);
    }
}
