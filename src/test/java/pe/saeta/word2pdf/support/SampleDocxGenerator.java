package pe.saeta.word2pdf.support;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * Genera DOCX de prueba escribiendo OOXML directamente (sin Apache POI), con contenido 100 % ficticio:
 * contrato de varias páginas, encabezado con logotipo, pie con numeración PAGE/NUMPAGES, tabla con celdas
 * combinadas (gridSpan/vMerge), estilos mixtos en un párrafo, interlineados personalizados, saltos de página,
 * salto de sección a Carta horizontal y bloque de firmas.
 * <p>
 * {@code main} escribe los ejemplos versionados en {@code samples/} y {@code src/test/resources/samples/}.
 */
public final class SampleDocxGenerator {

    public static final String CT_DOCX_MAIN =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";

    /** A4 vertical en twips. */
    public static final int A4_W = 11906;
    public static final int A4_H = 16838;
    /** Carta (Letter) en twips. */
    public static final int LETTER_W = 12240;
    public static final int LETTER_H = 15840;

    public record Options(int clausePages, boolean table, boolean headerFooter, boolean landscapeAnnex,
                          String bodyFont, String headingFont) {
        public static Options fullContract() {
            return new Options(3, true, true, true, "Times New Roman", "Arial");
        }
    }

    private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
            + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
            + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
            + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"";
    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";

    private static final String[] FILLER = {
            "Las partes declaran que actúan de buena fe y que la información consignada en el presente contrato es "
                    + "ficticia y ha sido generada exclusivamente con fines de prueba del servicio de conversión.",
            "EL PROVEEDOR se obliga a prestar los servicios descritos con diligencia profesional, respetando los "
                    + "plazos, niveles de servicio y condiciones técnicas acordadas en los anexos correspondientes.",
            "EL CLIENTE se obliga a pagar la contraprestación pactada dentro de los treinta (30) días calendario "
                    + "siguientes a la recepción de la factura, mediante transferencia a la cuenta designada.",
            "Cualquier modificación al presente contrato deberá constar por escrito y ser suscrita por los "
                    + "representantes legales de ambas partes, debidamente facultados para ello."
    };

    private SampleDocxGenerator() {
    }

    public static byte[] fullContract() {
        return contract(Options.fullContract());
    }

    /** Documento mínimo de una página (A4) sin tablas ni encabezados. */
    public static byte[] minimal() {
        return contract(new Options(1, false, false, false, "Times New Roman", "Arial"));
    }

    public static byte[] contract(Options o) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", utf8(contentTypes(o)));
        parts.put("_rels/.rels", utf8(XML_DECL
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>"));
        parts.put("word/_rels/document.xml.rels", utf8(documentRels(o)));
        parts.put("word/document.xml", utf8(document(o)));
        parts.put("word/styles.xml", utf8(styles(o)));
        if (o.headerFooter()) {
            parts.put("word/header1.xml", utf8(header()));
            parts.put("word/_rels/header1.xml.rels", utf8(XML_DECL
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rIdImg1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/logo.png\"/>"
                    + "</Relationships>"));
            parts.put("word/footer1.xml", utf8(footer()));
            parts.put("word/media/logo.png", logoPng());
        }
        return zip(parts);
    }

    public static byte[] zip(Map<String, byte[]> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ partes

    private static String contentTypes(Options o) {
        StringBuilder sb = new StringBuilder(XML_DECL)
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Default Extension=\"png\" ContentType=\"image/png\"/>")
                .append("<Override PartName=\"/word/document.xml\" ContentType=\"").append(CT_DOCX_MAIN).append("\"/>")
                .append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>");
        if (o.headerFooter()) {
            sb.append("<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>")
                    .append("<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private static String documentRels(Options o) {
        StringBuilder sb = new StringBuilder(XML_DECL)
                .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                .append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        if (o.headerFooter()) {
            sb.append("<Relationship Id=\"rIdH1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>")
                    .append("<Relationship Id=\"rIdF1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>");
        }
        return sb.append("</Relationships>").toString();
    }

    private static String styles(Options o) {
        return XML_DECL + "<w:styles " + NS + ">"
                + "<w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"" + esc(o.bodyFont()) + "\" w:hAnsi=\"" + esc(o.bodyFont()) + "\" w:cs=\"" + esc(o.bodyFont()) + "\"/>"
                + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"es-PE\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
                + "</w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
                + "<w:pPr><w:jc w:val=\"both\"/></w:pPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:pPr><w:jc w:val=\"center\"/><w:spacing w:after=\"240\"/></w:pPr>"
                + "<w:rPr><w:rFonts w:ascii=\"" + esc(o.headingFont()) + "\" w:hAnsi=\"" + esc(o.headingFont()) + "\"/>"
                + "<w:b/><w:sz w:val=\"32\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>"
                + "<w:rPr><w:rFonts w:ascii=\"" + esc(o.headingFont()) + "\" w:hAnsi=\"" + esc(o.headingFont()) + "\"/>"
                + "<w:b/><w:sz w:val=\"26\"/></w:rPr></w:style>"
                + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>"
                + "<w:tblPr><w:tblBorders>"
                + "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>"
                + "</w:tblBorders></w:tblPr></w:style>"
                + "</w:styles>";
    }

    private static String document(Options o) {
        StringBuilder b = new StringBuilder(XML_DECL).append("<w:document ").append(NS).append("><w:body>");
        b.append(p("Title", null, run("CONTRATO DE PRESTACIÓN DE SERVICIOS PROFESIONALES", null)));
        b.append(p(null, null, run("Documento ficticio generado para pruebas. No contiene datos reales.", "<w:i/>")));
        b.append(p(null, null,
                run("Conste por el presente documento el contrato que celebran, de una parte, ", null)
                        + run("EMPRESA FICTICIA S.A.C.", "<w:b/>")
                        + run(", con RUC N.° 20000000001, a quien en adelante se denominará ", null)
                        + run("EL PROVEEDOR", "<w:b/><w:u w:val=\"single\"/>")
                        + run("; y de la otra parte, ", null)
                        + run("CLIENTE DE PRUEBA S.A.", "<w:b/><w:i/>")
                        + run(", a quien se denominará ", null)
                        + run("EL CLIENTE", "<w:b/><w:color w:val=\"1F3864\"/><w:sz w:val=\"24\"/>")
                        + run(", en los términos y condiciones siguientes:", null)));

        int clause = 1;
        for (int page = 0; page < o.clausePages(); page++) {
            for (int c = 0; c < 2; c++) {
                b.append(p("Heading1", null, run("CLÁUSULA " + ordinal(clause++), null)));
                for (int k = 0; k < FILLER.length; k++) {
                    String spacing = k == 1 ? "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/>"
                            : k == 2 ? "<w:spacing w:line=\"280\" w:lineRule=\"exact\"/>" : null;
                    b.append(p(null, spacing, run(FILLER[k], null)));
                }
            }
            if (page == 0 && o.table()) {
                b.append(p("Heading1", null, run("CONTRAPRESTACIÓN", null)));
                b.append(feeTable());
                b.append(p(null, null, run("Los importes no incluyen el Impuesto General a las Ventas.", "<w:sz w:val=\"18\"/>")));
            }
            if (page < o.clausePages() - 1) {
                b.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            }
        }

        b.append(p(null, null, run("En señal de conformidad, las partes suscriben el presente documento en dos "
                + "ejemplares de igual tenor, en la ciudad de Lima.", null)));
        b.append(signatureBlock());

        if (o.landscapeAnnex()) {
            // Fin de la sección 1 (A4 vertical); comienza el anexo en Carta horizontal.
            b.append("<w:p><w:pPr>").append(sectPr(o, A4_W, A4_H, false, "nextPage")).append("</w:pPr></w:p>");
            b.append(p("Title", null, run("ANEXO 1 — CRONOGRAMA (Carta horizontal)", null)));
            if (o.table()) {
                b.append(feeTable());
            }
            b.append(p(null, null, run("Fin del anexo.", null)));
            b.append(sectPr(o, LETTER_H, LETTER_W, true, "nextPage"));
        } else {
            b.append(sectPr(o, A4_W, A4_H, false, null));
        }
        return b.append("</w:body></w:document>").toString();
    }

    private static String sectPr(Options o, int w, int h, boolean landscape, String type) {
        StringBuilder s = new StringBuilder("<w:sectPr>");
        if (o.headerFooter()) {
            s.append("<w:headerReference w:type=\"default\" r:id=\"rIdH1\"/>")
                    .append("<w:footerReference w:type=\"default\" r:id=\"rIdF1\"/>");
        }
        if (type != null) {
            s.append("<w:type w:val=\"").append(type).append("\"/>");
        }
        s.append("<w:pgSz w:w=\"").append(w).append("\" w:h=\"").append(h).append("\"")
                .append(landscape ? " w:orient=\"landscape\"" : "").append("/>")
                .append("<w:pgMar w:top=\"1418\" w:right=\"1418\" w:bottom=\"1418\" w:left=\"1701\" w:header=\"709\" w:footer=\"709\" w:gutter=\"0\"/>")
                .append("<w:cols w:space=\"708\"/>")
                .append("</w:sectPr>");
        return s.toString();
    }

    private static String feeTable() {
        return "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"8500\" w:type=\"dxa\"/></w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"2500\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>"
                + "<w:tr>" + cell("<w:gridSpan w:val=\"2\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"D9E2F3\"/>", 5500, "Concepto", true)
                + cell("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"D9E2F3\"/>", 3000, "Importe", true) + "</w:tr>"
                + "<w:tr>" + cell("<w:vMerge w:val=\"restart\"/><w:vAlign w:val=\"center\"/>", 3000, "Servicios de consultoría", false)
                + cell(null, 2500, "Pago mensual", false) + cell(null, 3000, "S/ 1,000.00", false) + "</w:tr>"
                + "<w:tr>" + cell("<w:vMerge/>", 3000, "", false)
                + cell(null, 2500, "Pago anual", false) + cell(null, 3000, "S/ 12,000.00", false) + "</w:tr>"
                + "<w:tr>" + cell("<w:gridSpan w:val=\"2\"/>", 5500, "Total referencial", true)
                + cell(null, 3000, "S/ 13,000.00", true) + "</w:tr>"
                + "</w:tbl>";
    }

    private static String cell(String tcPrExtra, int width, String text, boolean bold) {
        return "<w:tc><w:tcPr><w:tcW w:w=\"" + width + "\" w:type=\"dxa\"/>" + (tcPrExtra != null ? tcPrExtra : "")
                + "</w:tcPr>" + p(null, "<w:spacing w:after=\"0\"/>", text.isEmpty() ? "" : run(text, bold ? "<w:b/>" : null))
                + "</w:tc>";
    }

    private static String signatureBlock() {
        String noBorders = "<w:tblBorders><w:top w:val=\"nil\"/><w:left w:val=\"nil\"/><w:bottom w:val=\"nil\"/>"
                + "<w:right w:val=\"nil\"/><w:insideH w:val=\"nil\"/><w:insideV w:val=\"nil\"/></w:tblBorders>";
        String col1 = p(null, "<w:spacing w:before=\"960\" w:after=\"0\"/><w:jc w:val=\"center\"/>", run("______________________________", null))
                + p(null, "<w:spacing w:after=\"0\"/><w:jc w:val=\"center\"/>", run("Firma — EL PROVEEDOR", "<w:b/>"))
                + p(null, "<w:jc w:val=\"center\"/>", run("Representante legal (ficticio)", null));
        String col2 = p(null, "<w:spacing w:before=\"960\" w:after=\"0\"/><w:jc w:val=\"center\"/>", run("______________________________", null))
                + p(null, "<w:spacing w:after=\"0\"/><w:jc w:val=\"center\"/>", run("Firma — EL CLIENTE", "<w:b/>"))
                + p(null, "<w:jc w:val=\"center\"/>", run("Representante legal (ficticio)", null));
        return "<w:tbl><w:tblPr><w:tblW w:w=\"8500\" w:type=\"dxa\"/>" + noBorders + "</w:tblPr>"
                + "<w:tblGrid><w:gridCol w:w=\"4250\"/><w:gridCol w:w=\"4250\"/></w:tblGrid>"
                + "<w:tr><w:trPr><w:cantSplit/></w:trPr>"
                + "<w:tc><w:tcPr><w:tcW w:w=\"4250\" w:type=\"dxa\"/></w:tcPr>" + col1 + "</w:tc>"
                + "<w:tc><w:tcPr><w:tcW w:w=\"4250\" w:type=\"dxa\"/></w:tcPr>" + col2 + "</w:tc>"
                + "</w:tr></w:tbl>"
                + "<w:p/>";
    }

    private static String header() {
        String drawing = "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
                + "<wp:extent cx=\"1143000\" cy=\"381000\"/><wp:docPr id=\"1\" name=\"Logo\"/>"
                + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"1\" name=\"logo.png\"/><pic:cNvPicPr/></pic:nvPicPr>"
                + "<pic:blipFill><a:blip r:embed=\"rIdImg1\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>"
                + "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1143000\" cy=\"381000\"/></a:xfrm>"
                + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"
                + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>";
        return XML_DECL + "<w:hdr " + NS + ">"
                + "<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>" + drawing
                + run("    EMPRESA FICTICIA S.A.C. — Contrato de prueba", "<w:sz w:val=\"16\"/><w:color w:val=\"595959\"/>")
                + "</w:p></w:hdr>";
    }

    private static String footer() {
        return XML_DECL + "<w:ftr " + NS + ">"
                + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
                + run("Página ", "<w:sz w:val=\"18\"/>")
                + "<w:fldSimple w:instr=\" PAGE \">" + run("1", "<w:sz w:val=\"18\"/>") + "</w:fldSimple>"
                + run(" de ", "<w:sz w:val=\"18\"/>")
                + "<w:fldSimple w:instr=\" NUMPAGES \">" + run("1", "<w:sz w:val=\"18\"/>") + "</w:fldSimple>"
                + "</w:p></w:ftr>";
    }

    private static String p(String style, String pPrExtra, String runs) {
        StringBuilder sb = new StringBuilder("<w:p>");
        if (style != null || pPrExtra != null) {
            sb.append("<w:pPr>");
            if (style != null) {
                sb.append("<w:pStyle w:val=\"").append(style).append("\"/>");
            }
            if (pPrExtra != null) {
                sb.append(pPrExtra);
            }
            sb.append("</w:pPr>");
        }
        return sb.append(runs).append("</w:p>").toString();
    }

    private static String run(String text, String rPr) {
        return "<w:r>" + (rPr != null ? "<w:rPr>" + rPr + "</w:rPr>" : "")
                + "<w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r>";
    }

    private static String ordinal(int n) {
        String[] names = {"PRIMERA", "SEGUNDA", "TERCERA", "CUARTA", "QUINTA", "SEXTA", "SÉPTIMA", "OCTAVA",
                "NOVENA", "DÉCIMA", "UNDÉCIMA", "DUODÉCIMA"};
        return n <= names.length ? names[n - 1] : String.valueOf(n);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] logoPng() {
        BufferedImage img = new BufferedImage(240, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x1F, 0x38, 0x64));
            g.fillRect(0, 0, 240, 80);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
            g.drawString("FICTICIA", 28, 52);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Escribe los ejemplos versionados del repositorio. */
    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path samples = Files.createDirectories(root.resolve("samples"));
        Path testSamples = Files.createDirectories(root.resolve("src/test/resources/samples"));
        byte[] full = fullContract();
        byte[] simple = minimal();
        Files.write(samples.resolve("contrato-ejemplo.docx"), full);
        Files.write(testSamples.resolve("contrato-ejemplo.docx"), full);
        Files.write(testSamples.resolve("contrato-simple.docx"), simple);
        System.out.println("Ejemplos generados en " + samples.toAbsolutePath() + " y " + testSamples.toAbsolutePath());
    }
}
