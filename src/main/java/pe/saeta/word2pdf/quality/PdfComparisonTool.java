package pe.saeta.word2pdf.quality;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Herramienta de control de calidad (no se ejecuta en producción): compara el PDF generado por ONLYOFFICE
 * con un PDF de referencia exportado desde Microsoft Word y detecta diferencias de:
 * <ul>
 *   <li>número de páginas y dimensiones de cada página;</li>
 *   <li>distribución visual (render a N DPI y porcentaje de píxeles distintos, con PNG de diferencias);</li>
 *   <li>desplazamiento de párrafos/líneas (posición vertical de líneas de texto emparejadas);</li>
 *   <li>posición de anclas (p.ej. "Firma", encabezados de tablas) para verificar tablas y áreas de firma.</li>
 * </ul>
 * Uso:
 * <pre>
 * java -cp word2pdf.jar -Dloader.main=pe.saeta.word2pdf.quality.PdfComparisonTool \
 *      org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --reference word.pdf --candidate onlyoffice.pdf [--out informe/] [--dpi 72] \
 *      [--anchors "Firma,EL ARRENDADOR"] [--line-tolerance 6] [--pixel-threshold 0.02]
 * </pre>
 * Código de salida: 0 si probablemente equivalentes, 1 si hay diferencias relevantes, 2 si error de uso.
 */
public final class PdfComparisonTool {

    public record Options(float dpi, List<String> anchors, double lineTolerancePt, double pixelDiffThreshold,
                          Path outDir) {
        public static Options defaults() {
            return new Options(72f, List.of("Firma"), 6.0, 0.02, null);
        }
    }

    private static final int GRAY_DIFF_THRESHOLD = 48;
    private static final float PAGE_SIZE_TOLERANCE_PT = 2f;
    private static final int MAX_WORST_LINES = 15;
    private static final int MATCH_LOOKAHEAD = 80;
    private static final int TEXT_SAMPLE_CHARS = 40;

    private PdfComparisonTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Map<String, String> opts = parseArgs(args);
        if (opts == null || !opts.containsKey("reference") || !opts.containsKey("candidate")) {
            err.println("Uso: --reference <word.pdf> --candidate <onlyoffice.pdf> [--out dir] [--dpi 72] "
                    + "[--anchors \"Firma,EL CLIENTE\"] [--line-tolerance 6] [--pixel-threshold 0.02]");
            return 2;
        }
        try {
            Options defaults = Options.defaults();
            Options options = new Options(
                    Float.parseFloat(opts.getOrDefault("dpi", String.valueOf(defaults.dpi()))),
                    opts.containsKey("anchors")
                            ? Arrays.stream(opts.get("anchors").split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                            : defaults.anchors(),
                    Double.parseDouble(opts.getOrDefault("line-tolerance", String.valueOf(defaults.lineTolerancePt()))),
                    Double.parseDouble(opts.getOrDefault("pixel-threshold", String.valueOf(defaults.pixelDiffThreshold()))),
                    opts.containsKey("out") ? Path.of(opts.get("out")) : null);
            ComparisonReport report = compare(Files.readAllBytes(Path.of(opts.get("reference"))),
                    Files.readAllBytes(Path.of(opts.get("candidate"))), options);
            JsonMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
            String json = mapper.writeValueAsString(report);
            if (options.outDir() != null) {
                Files.writeString(options.outDir().resolve("report.json"), json);
            }
            out.println(json);
            out.println();
            out.println(report.likelyEquivalent() ? "RESULTADO: sin diferencias relevantes detectadas"
                    : "RESULTADO: diferencias relevantes -> " + String.join("; ", report.findings()));
            return report.likelyEquivalent() ? 0 : 1;
        } catch (IOException | RuntimeException e) {
            err.println("Error: " + e.getMessage());
            return 2;
        }
    }

    public static ComparisonReport compare(byte[] referencePdf, byte[] candidatePdf, Options options) throws IOException {
        if (options.outDir() != null) {
            Files.createDirectories(options.outDir());
        }
        try (PDDocument ref = load(referencePdf); PDDocument cand = load(candidatePdf)) {
            List<String> findings = new ArrayList<>();
            int refPages = ref.getNumberOfPages();
            int candPages = cand.getNumberOfPages();
            boolean pageCountMatches = refPages == candPages;
            if (!pageCountMatches) {
                findings.add("número de páginas distinto (" + refPages + " vs " + candPages + ")");
            }

            List<ComparisonReport.PageComparison> pages = comparePages(ref, cand, options, findings);
            List<Line> refLines = extractLines(ref);
            List<Line> candLines = extractLines(cand);
            ComparisonReport.TextShift shift = compareText(refLines, candLines, options, findings);
            List<ComparisonReport.AnchorComparison> anchors = compareAnchors(refLines, candLines, options, findings);

            return new ComparisonReport(refPages, candPages, pageCountMatches, pages, shift, anchors,
                    findings.isEmpty(), List.copyOf(findings));
        }
    }

    private static PDDocument load(byte[] pdf) throws IOException {
        return Loader.loadPDF(pdf, "", null, null, IOUtils.createMemoryOnlyStreamCache());
    }

    // ---------------------------------------------------------------- páginas y píxeles

    private static List<ComparisonReport.PageComparison> comparePages(PDDocument ref, PDDocument cand, Options options,
                                                                      List<String> findings) throws IOException {
        List<ComparisonReport.PageComparison> result = new ArrayList<>();
        PDFRenderer refRenderer = new PDFRenderer(ref);
        PDFRenderer candRenderer = new PDFRenderer(cand);
        int common = Math.min(ref.getNumberOfPages(), cand.getNumberOfPages());
        int sizeMismatches = 0;
        int visualMismatches = 0;
        for (int i = 0; i < common; i++) {
            float[] refSize = size(ref.getPage(i));
            float[] candSize = size(cand.getPage(i));
            boolean sizeMatches = Math.abs(refSize[0] - candSize[0]) <= PAGE_SIZE_TOLERANCE_PT
                    && Math.abs(refSize[1] - candSize[1]) <= PAGE_SIZE_TOLERANCE_PT;
            if (!sizeMatches) {
                sizeMismatches++;
            }
            BufferedImage a = refRenderer.renderImageWithDPI(i, options.dpi(), ImageType.GRAY);
            BufferedImage b = candRenderer.renderImageWithDPI(i, options.dpi(), ImageType.GRAY);
            String diffName = options.outDir() != null ? String.format(Locale.ROOT, "diff-page-%03d.png", i + 1) : null;
            double ratio = pixelDiff(a, b, diffName != null ? options.outDir().resolve(diffName) : null);
            if (ratio > options.pixelDiffThreshold()) {
                visualMismatches++;
            }
            result.add(new ComparisonReport.PageComparison(i + 1, fmt(refSize), fmt(candSize), sizeMatches,
                    Math.round(ratio * 10000d) / 10000d, diffName));
        }
        if (sizeMismatches > 0) {
            findings.add(sizeMismatches + " página(s) con dimensiones distintas");
        }
        if (visualMismatches > 0) {
            findings.add(visualMismatches + " página(s) con diferencia visual > "
                    + (options.pixelDiffThreshold() * 100) + "% de píxeles");
        }
        return result;
    }

    private static float[] size(PDPage page) {
        PDRectangle box = page.getCropBox();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        return rotation == 90 || rotation == 270
                ? new float[] {box.getHeight(), box.getWidth()}
                : new float[] {box.getWidth(), box.getHeight()};
    }

    private static String fmt(float[] size) {
        return String.format(Locale.ROOT, "%.1fx%.1fpt", size[0], size[1]);
    }

    /** Proporción de píxeles distintos; el área no común se compara contra blanco. */
    static double pixelDiff(BufferedImage a, BufferedImage b, Path diffOut) throws IOException {
        int w = Math.max(a.getWidth(), b.getWidth());
        int h = Math.max(a.getHeight(), b.getHeight());
        BufferedImage diff = diffOut != null ? new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB) : null;
        long different = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int ga = gray(a, x, y);
                int gb = gray(b, x, y);
                boolean differs = Math.abs(ga - gb) > GRAY_DIFF_THRESHOLD;
                if (differs) {
                    different++;
                }
                if (diff != null) {
                    if (differs) {
                        // rojo: solo en referencia; azul: solo en candidato
                        diff.setRGB(x, y, ga < gb ? 0xE0_20_20 : 0x20_40_E0);
                    } else {
                        int light = 200 + (Math.min(ga, gb) * 55 / 255);
                        diff.setRGB(x, y, (light << 16) | (light << 8) | light);
                    }
                }
            }
        }
        if (diff != null) {
            ImageIO.write(diff, "png", diffOut.toFile());
        }
        return (double) different / ((long) w * h);
    }

    private static int gray(BufferedImage img, int x, int y) {
        if (x >= img.getWidth() || y >= img.getHeight()) {
            return 255;
        }
        return img.getRaster().getSample(x, y, 0);
    }

    // ---------------------------------------------------------------- texto

    record Line(int page, double x, double y, String text, String key) {
    }

    /**
     * Extrae líneas visuales completas. {@code writeString} se invoca por fragmento (Word suele emitir la línea entera;
     * ONLYOFFICE, en texto justificado sin glifos de espacio, la parte en palabras), así que se acumulan los
     * fragmentos hasta el separador de línea. La clave de comparación ignora todos los espacios para que el
     * emparejamiento no dependa de cómo cada generador codifica el espaciado.
     */
    static List<Line> extractLines(PDDocument doc) throws IOException {
        List<Line> lines = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            private final StringBuilder current = new StringBuilder();
            private TextPosition first;

            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (positions.isEmpty() || text.isBlank()) {
                    return;
                }
                if (first == null) {
                    first = positions.get(0);
                } else {
                    current.append(' ');
                }
                current.append(text);
            }

            @Override
            protected void writeLineSeparator() {
                flush();
            }

            @Override
            protected void writePageEnd() {
                flush();
            }

            private void flush() {
                String normalized = current.toString().replaceAll("\\s+", " ").trim();
                if (first != null && !normalized.isEmpty()) {
                    lines.add(new Line(getCurrentPageNo(), first.getXDirAdj(), first.getYDirAdj(), normalized,
                            normalized.replaceAll("\\s+", "").toLowerCase(Locale.ROOT)));
                }
                current.setLength(0);
                first = null;
            }
        };
        stripper.setSortByPosition(true);
        stripper.getText(doc);
        return lines;
    }

    private static ComparisonReport.TextShift compareText(List<Line> ref, List<Line> cand, Options options,
                                                          List<String> findings) {
        boolean[] used = new boolean[cand.size()];
        int pointer = 0;
        List<ComparisonReport.LineShift> shifts = new ArrayList<>();
        int unmatched = 0;
        int onDifferentPage = 0;
        for (Line r : ref) {
            int found = -1;
            for (int j = pointer; j < Math.min(cand.size(), pointer + MATCH_LOOKAHEAD); j++) {
                if (!used[j] && cand.get(j).key().equals(r.key())) {
                    found = j;
                    break;
                }
            }
            if (found < 0) {
                unmatched++;
                continue;
            }
            used[found] = true;
            pointer = found + 1;
            Line c = cand.get(found);
            if (c.page() != r.page()) {
                onDifferentPage++;
            }
            double dy = c.page() == r.page() ? c.y() - r.y() : Double.NaN;
            shifts.add(new ComparisonReport.LineShift(sample(r.text()), r.page(), c.page(), round(r.y()), round(c.y()),
                    Double.isNaN(dy) ? Double.NaN : round(dy)));
        }
        double max = 0;
        double sum = 0;
        int samePage = 0;
        int beyond = 0;
        for (ComparisonReport.LineShift s : shifts) {
            if (Double.isNaN(s.dyPt())) {
                continue;
            }
            double abs = Math.abs(s.dyPt());
            max = Math.max(max, abs);
            sum += abs;
            samePage++;
            if (abs > options.lineTolerancePt()) {
                beyond++;
            }
        }
        if (onDifferentPage > 0) {
            findings.add(onDifferentPage + " línea(s) de texto cambiaron de página (reflujo)");
        }
        if (beyond > 0) {
            findings.add(beyond + " línea(s) desplazadas más de " + options.lineTolerancePt() + " pt");
        }
        if (!ref.isEmpty() && unmatched > ref.size() / 10) {
            findings.add(unmatched + " de " + ref.size() + " líneas de la referencia sin equivalente exacto");
        }
        List<ComparisonReport.LineShift> worst = shifts.stream()
                .sorted(Comparator.comparingDouble((ComparisonReport.LineShift s) ->
                        Double.isNaN(s.dyPt()) ? Double.MAX_VALUE : Math.abs(s.dyPt())).reversed())
                .limit(MAX_WORST_LINES)
                .map(s -> Double.isNaN(s.dyPt())
                        ? new ComparisonReport.LineShift(s.text(), s.referencePage(), s.candidatePage(), s.referenceY(),
                        s.candidateY(), -1)
                        : s)
                .toList();
        return new ComparisonReport.TextShift(shifts.size(), unmatched, round(max),
                samePage == 0 ? 0 : round(sum / samePage), beyond, onDifferentPage, worst);
    }

    private static List<ComparisonReport.AnchorComparison> compareAnchors(List<Line> ref, List<Line> cand,
                                                                          Options options, List<String> findings) {
        List<ComparisonReport.AnchorComparison> result = new ArrayList<>();
        // La búsqueda es insensible a mayúsculas: "Firma" y "FIRMA" son la misma ancla.
        List<String> distinctAnchors = options.anchors().stream()
                .collect(java.util.stream.Collectors.toMap(a -> a.toLowerCase(Locale.ROOT), a -> a, (a, b) -> a,
                        java.util.LinkedHashMap::new))
                .values().stream().toList();
        for (String anchor : distinctAnchors) {
            String key = anchor.toLowerCase(Locale.ROOT);
            List<Line> inRef = ref.stream().filter(l -> l.key().contains(key)).toList();
            List<Line> inCand = cand.stream().filter(l -> l.key().contains(key)).toList();
            int n = Math.max(inRef.size(), inCand.size());
            for (int i = 0; i < n; i++) {
                Line r = i < inRef.size() ? inRef.get(i) : null;
                Line c = i < inCand.size() ? inCand.get(i) : null;
                Double dy = r != null && c != null && r.page() == c.page() ? round(c.y() - r.y()) : null;
                result.add(new ComparisonReport.AnchorComparison(anchor, i + 1,
                        r != null ? r.page() : null, r != null ? round(r.y()) : null,
                        c != null ? c.page() : null, c != null ? round(c.y()) : null, dy));
                if (r == null || c == null) {
                    findings.add("ancla '" + anchor + "' #" + (i + 1) + " ausente en "
                            + (r == null ? "la referencia" : "el candidato"));
                } else if (r.page() != c.page()) {
                    findings.add("ancla '" + anchor + "' #" + (i + 1) + " cambió de página (" + r.page() + " -> "
                            + c.page() + ")");
                } else if (Math.abs(dy) > options.lineTolerancePt()) {
                    findings.add("ancla '" + anchor + "' #" + (i + 1) + " desplazada " + dy + " pt");
                }
            }
        }
        return result;
    }

    private static String sample(String text) {
        return text.length() <= TEXT_SAMPLE_CHARS ? text : text.substring(0, TEXT_SAMPLE_CHARS) + "…";
    }

    private static double round(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                return null;
            }
            map.put(args[i].substring(2), args[++i]);
        }
        return map;
    }
}
