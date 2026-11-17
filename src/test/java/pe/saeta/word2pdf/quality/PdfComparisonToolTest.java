package pe.saeta.word2pdf.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pe.saeta.word2pdf.support.TestPdfs;

class PdfComparisonToolTest {

    private static final List<String> LINES = List.of(
            "CONTRATO DE PRUEBA", "Clausula primera del documento ficticio", "Clausula segunda con mas texto",
            "Clausula tercera", "Firma del proveedor", "Firma del cliente");

    @Test
    void identicalPdfsAreEquivalent() throws Exception {
        byte[] pdf = TestPdfs.lines(LINES, 100, 30);
        ComparisonReport report = PdfComparisonTool.compare(pdf, pdf, PdfComparisonTool.Options.defaults());

        assertThat(report.likelyEquivalent()).isTrue();
        assertThat(report.findings()).isEmpty();
        assertThat(report.pages()).singleElement().satisfies(p -> {
            assertThat(p.sizeMatches()).isTrue();
            assertThat(p.pixelDiffRatio()).isZero();
        });
        assertThat(report.textShift().matchedLines()).isEqualTo(LINES.size());
        assertThat(report.anchors()).hasSize(2).allSatisfy(a -> assertThat(a.dyPt()).isZero());
    }

    @Test
    void detectsShiftedParagraphsAndSignatures() throws Exception {
        byte[] reference = TestPdfs.lines(LINES, 100, 30);
        byte[] shifted = TestPdfs.lines(LINES, 124, 30);
        ComparisonReport report = PdfComparisonTool.compare(reference, shifted, PdfComparisonTool.Options.defaults());

        assertThat(report.likelyEquivalent()).isFalse();
        assertThat(report.textShift().maxAbsShiftPt()).isCloseTo(24.0, org.assertj.core.data.Offset.offset(0.5));
        assertThat(report.textShift().linesBeyondTolerance()).isEqualTo(LINES.size());
        assertThat(report.anchors()).allSatisfy(a -> assertThat(a.dyPt()).isCloseTo(24.0, org.assertj.core.data.Offset.offset(0.5)));
        assertThat(report.pages().get(0).pixelDiffRatio()).isGreaterThan(0);
    }

    @Test
    void detectsPageCountAndSizeDifferences() throws Exception {
        ComparisonReport report = PdfComparisonTool.compare(TestPdfs.a4(2), TestPdfs.pages(3, PDRectangle.LETTER),
                PdfComparisonTool.Options.defaults());
        assertThat(report.pageCountMatches()).isFalse();
        assertThat(report.pages()).hasSize(2).allSatisfy(p -> assertThat(p.sizeMatches()).isFalse());
        assertThat(report.findings()).anyMatch(f -> f.contains("número de páginas"));
    }

    @Test
    void cliWritesReportAndDiffImages(@TempDir Path dir) throws Exception {
        Path ref = Files.write(dir.resolve("ref.pdf"), TestPdfs.lines(LINES, 100, 30));
        Path cand = Files.write(dir.resolve("cand.pdf"), TestPdfs.lines(LINES, 130, 30));
        Path out = dir.resolve("out");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        int exit = PdfComparisonTool.run(new String[] {"--reference", ref.toString(), "--candidate", cand.toString(),
                "--out", out.toString(), "--anchors", "Firma"}, new PrintStream(stdout), new PrintStream(new ByteArrayOutputStream()));

        assertThat(exit).isEqualTo(1);
        assertThat(out.resolve("report.json")).exists();
        assertThat(out.resolve("diff-page-001.png")).exists();
        assertThat(stdout.toString(StandardCharsets.UTF_8)).contains("RESULTADO");
    }

    @Test
    void cliRejectsMissingArguments() {
        assertThat(PdfComparisonTool.run(new String[] {"--reference"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()))).isEqualTo(2);
    }
}
