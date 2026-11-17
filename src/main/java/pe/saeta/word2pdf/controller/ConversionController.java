package pe.saeta.word2pdf.controller;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.fidelity.DocxLayout;
import pe.saeta.word2pdf.fidelity.DocxLayoutInspector;
import pe.saeta.word2pdf.service.ConversionResult;
import pe.saeta.word2pdf.service.DocumentConversionService;
import pe.saeta.word2pdf.validation.BoundedBodyReader;
import pe.saeta.word2pdf.validation.DocxValidator;

/**
 * API REST de conversión. Solo adapta HTTP ↔ servicio: no contiene lógica de conversión.
 */
@RestController
@RequestMapping("/api/v1/conversion")
public class ConversionController {

    public static final String HEADER_WARNINGS = "X-Conversion-Warnings";
    public static final String HEADER_SUSPECTED_FONTS = "X-Font-Substitution-Suspected";
    public static final String HEADER_PAGE_COUNT = "X-Pdf-Page-Count";
    private static final Pattern UNSAFE_FILENAME_CHARS = Pattern.compile("[^A-Za-z0-9._ -]");
    private static final Pattern UNSAFE_HEADER_CHARS = Pattern.compile("[^A-Za-z0-9 ._-]");
    private static final String DEFAULT_NAME = "documento";

    private final DocumentConversionService conversionService;
    private final DocxValidator docxValidator;
    private final DocxLayoutInspector layoutInspector;
    private final long maxInputBytes;

    public ConversionController(DocumentConversionService conversionService, DocxValidator docxValidator,
                                DocxLayoutInspector layoutInspector, ConversionProperties properties) {
        this.conversionService = conversionService;
        this.docxValidator = docxValidator;
        this.layoutInspector = layoutInspector;
        this.maxInputBytes = properties.maxInputSize().toBytes();
    }

    /** Cuerpo binario: los bytes del DOCX. {@code filename} solo se usa para nombrar el PDF devuelto. */
    @PostMapping(path = "/docx-to-pdf", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> convertBinary(HttpServletRequest request,
                                                @RequestParam(name = "filename", required = false) String filename)
            throws IOException {
        byte[] docx = BoundedBodyReader.read(request.getInputStream(), request.getContentLengthLong(), maxInputBytes);
        return pdfResponse(conversionService.convert(docx), filename);
    }

    /** Variante multipart (parte {@code file}) pensada para pruebas con Postman o formularios. */
    @PostMapping(path = "/docx-to-pdf/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> convertMultipart(@RequestPart("file") MultipartFile file) throws IOException {
        if (file.getSize() > maxInputBytes) {
            throw new ConversionException(ErrorCode.DOCUMENT_TOO_LARGE);
        }
        return pdfResponse(conversionService.convert(file.getBytes()), file.getOriginalFilename());
    }

    /**
     * Diagnóstico: tamaños de página y fuentes declaradas en un DOCX (sin convertirlo).
     * Útil para cruzar con las fuentes instaladas en ONLYOFFICE (scripts/check-fonts).
     */
    @PostMapping(path = "/docx/inspect", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DocxLayout inspect(HttpServletRequest request) throws IOException {
        byte[] docx = BoundedBodyReader.read(request.getInputStream(), request.getContentLengthLong(), maxInputBytes);
        docxValidator.validate(docx);
        return layoutInspector.inspect(docx);
    }

    private ResponseEntity<byte[]> pdfResponse(ConversionResult result, String requestedName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(pdfFileName(requestedName)).build());
        headers.setCacheControl(CacheControl.noStore());
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HEADER_PAGE_COUNT, Integer.toString(result.pageCount()));
        if (!result.fidelity().warnings().isEmpty()) {
            headers.set(HEADER_WARNINGS, String.join(",", result.fidelity().warnings()));
        }
        if (!result.fidelity().suspectedMissingFonts().isEmpty()) {
            String fonts = result.fidelity().suspectedMissingFonts().stream()
                    .map(f -> UNSAFE_HEADER_CHARS.matcher(f).replaceAll(""))
                    .filter(f -> !f.isBlank())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .reduce((a, b) -> a + "," + b).orElse("");
            headers.set(HEADER_SUSPECTED_FONTS, fonts.length() > 512 ? fonts.substring(0, 512) : fonts);
        }
        headers.setContentLength(result.pdf().length);
        return ResponseEntity.ok().headers(headers).body(result.pdf());
    }

    static String pdfFileName(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_NAME + ".pdf";
        }
        String name = requested.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = UNSAFE_FILENAME_CHARS.matcher(name).replaceAll("_").trim();
        if (name.isEmpty() || name.chars().allMatch(c -> c == '_' || c == '.')) {
            name = DEFAULT_NAME;
        }
        if (name.length() > 100) {
            name = name.substring(0, 100);
        }
        return name + ".pdf";
    }
}
