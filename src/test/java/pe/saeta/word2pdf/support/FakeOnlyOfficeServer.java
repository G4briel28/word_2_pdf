package pe.saeta.word2pdf.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Simulador de ONLYOFFICE Docs ({@code POST /converter/from-file} y {@code GET /healthcheck}) para pruebas sin Docker.
 * Verifica el contrato de la petición tal como lo documenta ONLYOFFICE 10.0: partes multipart permitidas
 * ({@code file}, {@code params}, {@code token}), JWT HS256 válido con {@code operation=converter} y sin {@code url}.
 * Si la verificación falla responde como el servidor real ({@code {"error":-8}} o {@code {"error":-7}}).
 */
public final class FakeOnlyOfficeServer implements AutoCloseable {

    public enum Mode { PDF, JSON_ERROR, NOT_PDF, HTTP_STATUS }

    public record Behavior(Mode mode, int pages, int errorCode, int httpStatus, long delayMillis) {
        public static Behavior pdf(int pages) {
            return new Behavior(Mode.PDF, pages, 0, 200, 0);
        }

        public static Behavior error(int code) {
            return new Behavior(Mode.JSON_ERROR, 0, code, 200, 0);
        }

        public static Behavior notPdf() {
            return new Behavior(Mode.NOT_PDF, 0, 0, 200, 0);
        }

        public static Behavior httpStatus(int status) {
            return new Behavior(Mode.HTTP_STATUS, 0, 0, status, 0);
        }

        public Behavior delayed(long millis) {
            return new Behavior(mode, pages, errorCode, httpStatus, millis);
        }
    }

    private static final Set<String> ALLOWED_PARTS = Set.of("file", "params", "token");

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final String secret;
    private volatile Behavior behavior = Behavior.pdf(1);
    private final AtomicInteger current = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> violations = new CopyOnWriteArrayList<>();
    private final List<JWTClaimsSet> receivedClaims = new CopyOnWriteArrayList<>();
    private final List<byte[]> receivedFiles = new CopyOnWriteArrayList<>();

    public FakeOnlyOfficeServer(String secret) {
        this.secret = secret;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(executor);
        server.createContext("/healthcheck", ex -> respond(ex, 200, "text/plain", "true".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/converter/from-file", this::convert);
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void setBehavior(Behavior behavior) {
        this.behavior = behavior;
    }

    public void reset() {
        behavior = Behavior.pdf(1);
        maxConcurrent.set(0);
        requests.set(0);
        violations.clear();
        receivedClaims.clear();
        receivedFiles.clear();
    }

    public int maxConcurrent() {
        return maxConcurrent.get();
    }

    public int requests() {
        return requests.get();
    }

    public List<String> violations() {
        return List.copyOf(violations);
    }

    public List<JWTClaimsSet> receivedClaims() {
        return List.copyOf(receivedClaims);
    }

    public List<byte[]> receivedFiles() {
        return List.copyOf(receivedFiles);
    }

    private void convert(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        int now = current.incrementAndGet();
        maxConcurrent.accumulateAndGet(now, Math::max);
        try {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, "text/plain", new byte[0]);
                return;
            }
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, byte[]> parts = parseMultipart(contentType, body);
            String violation = verify(parts, ex.getRequestHeaders().getFirst("Authorization"));
            if (violation != null) {
                violations.add(violation);
                int code = violation.startsWith("token") ? -8 : -7;
                respond(ex, 200, "application/json", ("{\"error\":" + code + "}").getBytes(StandardCharsets.UTF_8));
                return;
            }
            receivedFiles.add(parts.get("file"));
            Behavior b = behavior;
            if (b.delayMillis() > 0) {
                try {
                    Thread.sleep(b.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            switch (b.mode()) {
                case PDF -> {
                    ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"document.pdf\"");
                    respond(ex, 200, "application/pdf", TestPdfs.a4(b.pages()));
                }
                case JSON_ERROR -> respond(ex, 200, "application/json",
                        ("{\"error\":" + b.errorCode() + "}").getBytes(StandardCharsets.UTF_8));
                case NOT_PDF -> respond(ex, 200, "application/octet-stream",
                        "esto no es un pdf".getBytes(StandardCharsets.UTF_8));
                case HTTP_STATUS -> respond(ex, b.httpStatus(), "text/html",
                        "<html><body>Bad Gateway</body></html>".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // cliente desconectado (p.ej. timeout del lado del servicio)
        } finally {
            current.decrementAndGet();
            ex.close();
        }
    }

    private String verify(Map<String, byte[]> parts, String authorization) {
        for (String name : parts.keySet()) {
            if (!ALLOWED_PARTS.contains(name)) {
                return "input: parte no permitida " + name;
            }
        }
        if (!parts.containsKey("file") || parts.get("file").length == 0) {
            return "input: falta parte file";
        }
        if (secret == null) {
            return null;
        }
        String token = parts.containsKey("token") ? new String(parts.get("token"), StandardCharsets.UTF_8) : null;
        boolean fromHeader = false;
        if (token == null && authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
            fromHeader = true;
        }
        if (token == null) {
            return "token: ausente";
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new MACVerifier(secret.getBytes(StandardCharsets.UTF_8)))) {
                return "token: firma inválida";
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Map<String, Object> params = fromHeader ? castMap(claims.getClaim("payload")) : claims.getClaims();
            if (params == null) {
                return "token: falta payload";
            }
            if (!"converter".equals(params.get("operation"))) {
                return "token: operation distinto de converter";
            }
            if (params.containsKey("url")) {
                return "token: incluye url";
            }
            if (!"pdf".equals(params.get("outputtype")) || !"docx".equals(params.get("filetype"))) {
                return "token: filetype/outputtype inesperados";
            }
            if (claims.getExpirationTime() == null || claims.getExpirationTime().getTime() < System.currentTimeMillis()) {
                return "token: expirado";
            }
            receivedClaims.add(claims);
            return null;
        } catch (Exception e) {
            return "token: ilegible " + e.getClass().getSimpleName();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    static Map<String, byte[]> parseMultipart(String contentType, byte[] body) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return parts;
        }
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9).replace("\"", "");
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        List<Integer> positions = new ArrayList<>();
        for (int i = indexOf(body, delimiter, 0); i >= 0; i = indexOf(body, delimiter, i + delimiter.length)) {
            positions.add(i);
        }
        for (int k = 0; k + 1 < positions.size(); k++) {
            int start = positions.get(k) + delimiter.length + 2; // salta CRLF
            int end = positions.get(k + 1) - 2; // CRLF antes del delimitador
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII), start);
            if (headerEnd < 0 || headerEnd > end) {
                continue;
            }
            String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
            int nameIdx = headers.indexOf("name=\"");
            String name = headers.substring(nameIdx + 6, headers.indexOf('"', nameIdx + 6));
            byte[] content = new byte[end - (headerEnd + 4)];
            System.arraycopy(body, headerEnd + 4, content, 0, content.length);
            parts.put(name, content);
        }
        return parts;
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void respond(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
