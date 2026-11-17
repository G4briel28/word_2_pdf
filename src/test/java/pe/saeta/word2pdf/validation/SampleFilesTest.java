package pe.saeta.word2pdf.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import pe.saeta.word2pdf.fidelity.DocxLayoutInspector;
import pe.saeta.word2pdf.support.TestConfigs;

/** Los DOCX de ejemplo versionados (generados por SampleDocxGenerator) son válidos. */
class SampleFilesTest {

    @ParameterizedTest
    @ValueSource(strings = {"/samples/contrato-ejemplo.docx", "/samples/contrato-simple.docx"})
    void committedSamplesAreValidDocx(String resource) throws Exception {
        byte[] docx;
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertThat(in).as("falta " + resource + " (ejecute SampleDocxGenerator)").isNotNull();
            docx = in.readAllBytes();
        }
        assertThatCode(() -> new DocxValidator(TestConfigs.conversion()).validate(docx)).doesNotThrowAnyException();
        assertThat(new DocxLayoutInspector().inspect(docx).sections()).isNotEmpty();
    }
}
