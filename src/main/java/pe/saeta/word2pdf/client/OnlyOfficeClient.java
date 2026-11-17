package pe.saeta.word2pdf.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import pe.saeta.word2pdf.config.ConversionProperties;
import pe.saeta.word2pdf.config.OnlyOfficeProperties;
import pe.saeta.word2pdf.exception.ConversionException;
import pe.saeta.word2pdf.exception.ErrorCode;
import pe.saeta.word2pdf.service.ConversionEngine;
import pe.saeta.word2pdf.validation.BoundedBodyReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cliente de la Conversion API de ONLYOFFICE Docs 10.x, endpoint {@code POST /converter/from-file}.
 * <p>
 * El DOCX viaja en la parte multipart {@code file} tal cual se recibió (sin modificar). Los parámetros
 * de conversión van firmados en un JWT con {@code operation=converter} y sin {@code url}, como exige 10.0.
 * La conversión es síncrona: ONLYOFFICE responde con el binario convertido o con JSON {@code {"error": n}}.
 */
@Component
public class OnlyOfficeClient implements ConversionEngine {

    private static final Logger log = LoggerFactory.getLogger(OnlyOfficeClient.class);

    static final String FROM_FILE_PATH = "/converter/from-file";
    static final String DOCX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    /** Nombre neutro: nunca se envía el nombre original del contrato. */
    static final String NEUTRAL_TITLE = "document.docx";

    private final HttpClient httpClient;
    private final OnlyOfficeProperties properties;
    private final OnlyOfficeJwtSigner jwtSigner;
    private final ObjectMapper objectMapper;
    private final long maxOutputBytes;
    private final URI fromFileUri;

    public OnlyOfficeClient(HttpClient httpClient, OnlyOfficeProperties properties, OnlyOfficeJwtSigner jwtSigner,
                            ObjectMapper objectMapper, ConversionProperties conversionProperties) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.jwtSigner = jwtSigner;
        this.objectMapper = objectMapper;
        this.maxOutputBytes = conversionProperties.maxOutputSize().toBytes();
        this.fromFileUri = properties.baseUrl().resolve(FROM_FILE_PATH);
    }

    @Override
    public String name() {
        return "onlyoffice";
    }

    @Override
    public byte[] convertDocxToPdf(byte[] docxBytes) {
        String conversionKey = UUID.randomUUID().toString();
        HttpRequest request = buildRequest(docxBytes, conversionKey);
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new ConversionException(ErrorCode.ENGINE_UNAVAILABLE, null, "Sin conexión con ONLYOFFICE", e);
        } catch (HttpTimeoutException e) {
            throw new ConversionException(ErrorCode.CONVERSION_TIMEOUT, null,
                    "Sin respuesta de ONLYOFFICE en " + properties.conversionTimeout(), e);
        } catch (IOException e) {
            throw new ConversionException(ErrorCode.ENGINE_UNAVAILABLE, null, "Error de E/S con ONLYOFFICE", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConversionException(ErrorCode.ENGINE_UNAVAILABLE, null, "Conversión interrumpida", e);
        }
        return handleResponse(response, conversionKey);
    }

    HttpRequest buildRequest(byte[] docxBytes, String conversionKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("filetype", "docx");
        params.put("outputtype", "pdf");
        params.put("key", conversionKey);
        params.put("title", NEUTRAL_TITLE);
        // Sin "url": el archivo viaja en la parte "file". No se fijan opciones de maquetación para no
        // alterar el diseño del documento (tamaño de papel, márgenes, orientación se respetan del DOCX).

        String boundary = "----word2pdf" + UUID.randomUUID().toString().replace("-", "");
        MultipartBody body = new MultipartBody(boundary);
        body.filePart("file", NEUTRAL_TITLE, DOCX_MEDIA_TYPE, docxBytes);

        HttpRequest.Builder builder = HttpRequest.newBuilder(fromFileUri)
                .timeout(properties.conversionTimeout())
                .header("Accept", "application/pdf, application/json");

        if (jwtSigner.enabled()) {
            Map<String, Object> claims = new LinkedHashMap<>(params);
            claims.put("operation", "converter");
            if (properties.jwt().transport() == OnlyOfficeProperties.JwtTransport.HEADER) {
                // En cabecera, ONLYOFFICE espera los parámetros envueltos en "payload".
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("payload", claims);
                wrapped.put("operation", "converter");
                builder.header("Authorization", "Bearer " + jwtSigner.sign(wrapped));
            } else {
                body.textPart("token", jwtSigner.sign(claims));
            }
        } else {
            body.textPart("params", objectMapper.writeValueAsString(params));
        }

        return builder
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(body.finish()))
                .build();
    }

    private byte[] handleResponse(HttpResponse<InputStream> response, String conversionKey) {
        int status = response.statusCode();
        String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        byte[] body;
        try (InputStream in = response.body()) {
            body = BoundedBodyReader.read(in, response.headers().firstValueAsLong("Content-Length").orElse(-1),
                    maxOutputBytes);
        } catch (ConversionException e) {
            throw new ConversionException(ErrorCode.INVALID_PDF_OUTPUT, null, "Respuesta del motor excede el máximo");
        } catch (IOException e) {
            throw new ConversionException(ErrorCode.ENGINE_UNAVAILABLE, null, "Error leyendo respuesta de ONLYOFFICE", e);
        }

        if (status == 413) {
            throw new ConversionException(ErrorCode.DOCUMENT_TOO_LARGE, null,
                    "ONLYOFFICE rechazó el tamaño (FileConverter.converter.maxDownloadBytes)");
        }
        if (status == 401 || status == 403) {
            log.error("ONLYOFFICE rechazó la autenticación (HTTP {}). Verifique que ONLYOFFICE_JWT_SECRET coincide.", status);
            throw new ConversionException(ErrorCode.ENGINE_AUTH_ERROR, null, "HTTP " + status);
        }
        if (looksLikeJson(contentType, body)) {
            throw mapJsonError(body, status, conversionKey);
        }
        if (status >= 500) {
            throw new ConversionException(ErrorCode.ENGINE_UNAVAILABLE, null, "ONLYOFFICE respondió HTTP " + status);
        }
        if (status != 200) {
            throw new ConversionException(ErrorCode.ENGINE_ERROR, null, "Respuesta inesperada HTTP " + status);
        }
        return body;
    }

    private ConversionException mapJsonError(byte[] body, int status, String conversionKey) {
        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            return new ConversionException(ErrorCode.ENGINE_ERROR, null, "JSON ilegible de ONLYOFFICE (HTTP " + status + ")");
        }
        JsonNode error = json.get("error");
        if (error != null && error.isNumber()) {
            OnlyOfficeErrorCode code = OnlyOfficeErrorCode.fromCode(error.asInt());
            if (code == OnlyOfficeErrorCode.TOKEN) {
                log.error("ONLYOFFICE devolvió error -8 (token inválido). Verifique ONLYOFFICE_JWT_SECRET y el transporte JWT.");
            }
            return new ConversionException(code.mappedTo(), null,
                    "ONLYOFFICE error " + code.code() + " (" + code.description() + ") key=" + conversionKey);
        }
        JsonNode endConvert = json.get("endConvert");
        if (endConvert != null && !endConvert.asBoolean()) {
            return new ConversionException(ErrorCode.ENGINE_ERROR, null,
                    "ONLYOFFICE respondió en modo asíncrono inesperadamente (endConvert=false)");
        }
        return new ConversionException(ErrorCode.ENGINE_ERROR, null, "JSON inesperado de ONLYOFFICE (HTTP " + status + ")");
    }

    private static boolean looksLikeJson(String contentType, byte[] body) {
        if (contentType.contains("json")) {
            return true;
        }
        for (byte b : body) {
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') {
                continue;
            }
            return b == '{';
        }
        return false;
    }

    /** Construye el cuerpo multipart sin copiar los bytes del documento. */
    static final class MultipartBody {
        private final String boundary;
        private final List<byte[]> chunks = new java.util.ArrayList<>();

        MultipartBody(String boundary) {
            this.boundary = boundary;
        }

        void filePart(String name, String filename, String contentType, byte[] content) {
            chunks.add(ascii("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n"
                    + "Content-Type: " + contentType + "\r\n\r\n"));
            chunks.add(content);
            chunks.add(ascii("\r\n"));
        }

        void textPart(String name, String value) {
            chunks.add(ascii("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + name + "\"\r\n"
                    + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"));
            chunks.add(value.getBytes(StandardCharsets.UTF_8));
            chunks.add(ascii("\r\n"));
        }

        List<byte[]> finish() {
            chunks.add(ascii("--" + boundary + "--\r\n"));
            return List.copyOf(chunks);
        }

        private static byte[] ascii(String s) {
            return s.getBytes(StandardCharsets.US_ASCII);
        }
    }
}
