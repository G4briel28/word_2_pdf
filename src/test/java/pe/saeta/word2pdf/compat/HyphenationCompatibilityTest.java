package pe.saeta.word2pdf.compat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import pe.saeta.word2pdf.support.SampleDocxGenerator;
import pe.saeta.word2pdf.support.SampleDocxGenerator.Hyphenation;
import pe.saeta.word2pdf.support.TestConfigs;
import pe.saeta.word2pdf.validation.DocxValidator;

class HyphenationCompatibilityTest {

    private final HyphenationCompatibility compat = new HyphenationCompatibility(TestConfigs.conversion());

    @Test
    void removesAutoHyphenationWhenDefaultsSuppressIt() {
        byte[] docx = SampleDocxGenerator.hyphenationContract(Hyphenation.ON_SUPPRESSED_BY_DEFAULT);
        byte[] copy = docx.clone();

        HyphenationCompatibility.Result result = compat.apply(docx);

        assertThat(result.adjusted()).isTrue();
        assertThat(docx).as("el arreglo recibido no se modifica").isEqualTo(copy);
        Map<String, byte[]> before = SampleDocxGenerator.unzip(docx);
        Map<String, byte[]> after = SampleDocxGenerator.unzip(result.docx());
        assertThat(after.keySet()).containsExactlyElementsOf(before.keySet());
        String settings = new String(after.get("word/settings.xml"), StandardCharsets.UTF_8);
        assertThat(settings).doesNotContain("autoHyphenation").contains("<w:hyphenationZone w:val=\"0\"/>");
        before.forEach((name, bytes) -> {
            if (!name.equals("word/settings.xml")) {
                assertThat(after.get(name)).as(name + " sin cambios").isEqualTo(bytes);
            }
        });
        new DocxValidator(TestConfigs.conversion()).validate(result.docx());
    }

    @ParameterizedTest
    @EnumSource(value = Hyphenation.class, names = {"OFF", "ON", "ON_SUPPRESSED_BY_DEFAULT_REENABLED_BY_STYLE",
            "ON_SUPPRESSED_BY_DEFAULT_REENABLED_BY_PARAGRAPH"})
    void keepsOriginalWhenWordAndOnlyOfficeAlreadyAgree(Hyphenation mode) {
        byte[] docx = SampleDocxGenerator.hyphenationContract(mode);
        HyphenationCompatibility.Result result = compat.apply(docx);
        assertThat(result.adjusted()).isFalse();
        assertThat(result.docx()).isSameAs(docx);
    }

    @Test
    void canBeDisabled() {
        byte[] docx = SampleDocxGenerator.hyphenationContract(Hyphenation.ON_SUPPRESSED_BY_DEFAULT);
        HyphenationCompatibility disabled = new HyphenationCompatibility(TestConfigs.conversionWithHyphenationCompat(false));
        HyphenationCompatibility.Result result = disabled.apply(docx);
        assertThat(result.adjusted()).isFalse();
        assertThat(result.docx()).isSameAs(docx);
    }

    @Test
    void documentsWithoutSettingsAreUntouched() {
        byte[] docx = SampleDocxGenerator.fullContract();
        assertThat(compat.apply(docx).docx()).isSameAs(docx);
    }

    @Test
    void unreadableInputFallsBackToOriginal() {
        byte[] garbage = "no soy un zip".getBytes(StandardCharsets.UTF_8);
        HyphenationCompatibility.Result result = compat.apply(garbage);
        assertThat(result.adjusted()).isFalse();
        assertThat(result.docx()).isSameAs(garbage);
    }

    @Test
    void explicitFalseValueIsNotTreatedAsActive() throws Exception {
        String settings = "<w:settings xmlns:w=\"" + HyphenationCompatibility.W_NS + "\">"
                + "<w:autoHyphenation w:val=\"0\"/></w:settings>";
        assertThat(HyphenationCompatibility.autoHyphenationPrefix(settings.getBytes(StandardCharsets.UTF_8))).isNull();
    }

    @Test
    void handlesOtherNamespacePrefixes() throws Exception {
        String settings = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns0:settings xmlns:ns0=\""
                + HyphenationCompatibility.W_NS + "\"><ns0:zoom ns0:percent=\"100\"/>"
                + "<ns0:autoHyphenation ns0:val=\"true\"></ns0:autoHyphenation><ns0:defaultTabStop ns0:val=\"708\"/>"
                + "</ns0:settings>";
        byte[] bytes = settings.getBytes(StandardCharsets.UTF_8);
        String prefix = HyphenationCompatibility.autoHyphenationPrefix(bytes);
        assertThat(prefix).isEqualTo("ns0");

        byte[] result = HyphenationCompatibility.removeAutoHyphenation(bytes, prefix);

        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><ns0:settings xmlns:ns0=\"" + HyphenationCompatibility.W_NS
                        + "\"><ns0:zoom ns0:percent=\"100\"/><ns0:defaultTabStop ns0:val=\"708\"/></ns0:settings>");
    }

    @Test
    void nonUtf8SettingsAreNotRewritten() throws Exception {
        String settings = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><w:settings xmlns:w=\""
                + HyphenationCompatibility.W_NS + "\"><w:autoHyphenation/></w:settings>";
        assertThat(HyphenationCompatibility.removeAutoHyphenation(
                settings.getBytes(StandardCharsets.ISO_8859_1), "w")).isNull();
    }
}
