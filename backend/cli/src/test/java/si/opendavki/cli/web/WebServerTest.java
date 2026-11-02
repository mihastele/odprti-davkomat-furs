package si.opendavki.cli.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.opendavki.json.Json;

class WebServerTest {
    private static final String CSV = "type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency,fee,fee_currency\n"
            + "BUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,10,100,EUR,,\n"
            + "SELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR,,\n";

    @TempDir Path dir;
    private WebServer server;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void start() throws IOException {
        Path web = Files.createDirectories(dir.resolve("web"));
        Files.writeString(web.resolve("index.html"), "<html>ui</html>");
        Files.writeString(web.resolve("app.js"), "console.log(1)");
        Files.writeString(dir.resolve("secret.txt"), "TOP SECRET");
        server = new WebServer(0, web, dir.resolve("no.env"), Path.of("../../schemas"));
        server.start();
    }

    @AfterEach void stop() { server.close(); }

    private String base() { return "http://127.0.0.1:" + server.port(); }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
        if (token != null) b.header(WebServer.TOKEN_HEADER, token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token, String body, String... extraHeaders) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header(WebServer.TOKEN_HEADER, token);
        for (int i = 0; i < extraHeaders.length; i += 2) b.header(extraHeaders[i], extraHeaders[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    private String importBody() {
        return "{\"files\":[{\"name\":\"s.csv\",\"contentBase64\":\"" + b64(CSV) + "\"}]}";
    }

    /** Raw HTTP so that the Host header can be forged, which java.net.http refuses to do. */
    private String raw(String request) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            s.getInputStream().transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test void listensOnTheLoopbackAddressOnly() {
        assertTrue(server.url().startsWith("http://127.0.0.1:"));
        assertTrue(server.url().endsWith("#token=" + server.token()));
        assertTrue(server.token().length() >= 30);
    }

    @Test void theApiNeedsTheKey() throws Exception {
        assertEquals(401, get("/api/info", null).statusCode());
        assertEquals(401, get("/api/info", "wrong").statusCode());
        assertEquals(401, post("/api/import", null, importBody()).statusCode());
        assertEquals(200, get("/api/info", server.token()).statusCode());
    }

    @Test void foreignHostsAndOriginsAreRefused() throws Exception {
        String req = "GET /api/info HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\n" + WebServer.TOKEN_HEADER + ": " + server.token() + "\r\nConnection: close\r\n\r\n";
        assertTrue(raw(req).startsWith("HTTP/1.1 403"), "a forged Host header (DNS rebinding) must be refused");
        assertEquals(403, post("/api/describe", server.token(), "{}", "Origin", "http://evil.example").statusCode());
        assertEquals(200, get("/index.html", null).statusCode()); // a plain same-origin page load stays allowed
        String staticForged = "GET /index.html HTTP/1.1\r\nHost: evil.example:" + server.port() + "\r\nConnection: close\r\n\r\n";
        assertTrue(raw(staticForged).startsWith("HTTP/1.1 403"));
    }

    @Test void infoListsAdaptersSchemasAndSettings() throws Exception {
        JsonNode info = Json.MAPPER.readTree(get("/api/info", server.token()).body());
        java.util.Set<String> ids = new java.util.HashSet<>();
        info.get("adapters").forEach(a -> ids.add(a.get("id").asText()));
        assertTrue(ids.containsAll(java.util.Set.of("generic", "ibkr", "trading212")), ids.toString());
        info.get("schemas").forEach(s -> assertEquals("OK", s.get("status").asText(), s.toString()));
        assertEquals("ECB", info.get("settings").get("fxSource").asText());
    }

    private static boolean absentOrNull(JsonNode n, String field) {
        return !n.has(field) || n.get(field).isNull(); // null properties are omitted from responses
    }

    @Test void importThenReportEndToEnd() throws Exception {
        HttpResponse<String> imp = post("/api/import", server.token(), importBody());
        assertEquals(200, imp.statusCode(), imp.body());
        JsonNode imported = Json.MAPPER.readTree(imp.body());
        assertFalse(imported.get("hasErrors").asBoolean(), imported.toString());
        assertEquals(2, imported.get("entries").size());
        assertEquals("BUY 10 @ 100 EUR", imported.get("entries").get(0).get("what").asText());
        assertEquals("SELL 4 @ 130 EUR", imported.get("entries").get(1).get("what").asText());
        assertEquals("s.csv:2", imported.get("entries").get(0).get("source").asText());
        assertEquals("generic", imported.get("files").get(0).get("adapter").asText());
        assertFalse(imported.has("ledger"), "the ledger stays on the server; the page gets display strings only");

        String reportBody = "{\"ledgerId\":\"" + imported.get("ledgerId").asText() + "\",\"year\":2024,\"taxNumber\":\"12345678\",\"allowIncomplete\":false}";
        HttpResponse<String> rep = post("/api/report", server.token(), reportBody);
        assertEquals(200, rep.statusCode(), rep.body());
        JsonNode r = Json.MAPPER.readTree(rep.body());
        assertTrue(r.get("complete").asBoolean());
        assertFalse(r.get("xmlWithheld").asBoolean());
        assertTrue(r.get("kdvpValidation").get("valid").asBoolean(), r.get("kdvpValidation").toString());
        assertTrue(r.get("kdvpXml").asText().contains("<ISIN>US0378331005</ISIN>"));
        assertEquals(0, new java.math.BigDecimal("120.00").compareTo(r.get("gains").get(0).get("gainEur").decimalValue())); // 4 x (130 - 100)
        assertTrue(r.get("auditMarkdown").asText().contains("# Audit report 2024"));
        assertTrue(absentOrNull(r, "divXml"));
    }

    @Test void amountsReachThePageExactlyAndTheLedgerCanBeDownloadedUnchanged() throws Exception {
        // 16 significant digits: a JavaScript number could not hold this exactly
        String precise = "type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency\nBUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,0.1234567890123456,100.1234567890123456,EUR\n";
        JsonNode imported = Json.MAPPER.readTree(post("/api/import", server.token(), "{\"files\":[{\"name\":\"p.csv\",\"contentBase64\":\"" + b64(precise) + "\"}]}").body());
        assertEquals("BUY 0.1234567890123456 @ 100.1234567890123456 EUR", imported.get("entries").get(0).get("what").asText());
        HttpResponse<String> download = get("/api/ledger/" + imported.get("ledgerId").asText() + "/download", server.token());
        assertEquals(200, download.statusCode());
        assertTrue(download.body().contains("0.1234567890123456") && download.body().contains("100.1234567890123456"), download.body());
        assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains("ledger.json"));
        assertEquals(400, post("/api/report", server.token(), "{\"ledgerId\":\"nope\",\"year\":2024,\"taxNumber\":\"12345678\",\"allowIncomplete\":false}").statusCode());
        assertEquals(400, get("/api/ledger/unknown/download", server.token()).statusCode());
    }

    @Test void aSavedLedgerCanBeOpenedAgain() throws Exception {
        JsonNode imported = Json.MAPPER.readTree(post("/api/import", server.token(), importBody()).body());
        String saved = get("/api/ledger/" + imported.get("ledgerId").asText() + "/download", server.token()).body();
        JsonNode loaded = Json.MAPPER.readTree(post("/api/ledger", server.token(), Json.MAPPER.writeValueAsString(java.util.Map.of("text", saved))).body());
        assertEquals(2, loaded.get("entries").size());
        assertNotEquals(imported.get("ledgerId").asText(), loaded.get("ledgerId").asText());
        assertEquals(400, post("/api/ledger", server.token(), "{\"text\":\"not a ledger\"}").statusCode());
        assertEquals(400, post("/api/ledger", server.token(), "{\"text\":\"\"}").statusCode());
    }

    @Test void onlyTheLatestFewLedgersAreKept() throws Exception {
        String first = Json.MAPPER.readTree(post("/api/import", server.token(), importBody()).body()).get("ledgerId").asText();
        for (int i = 0; i < 5; i++) post("/api/import", server.token(), importBody());
        assertEquals(400, get("/api/ledger/" + first + "/download", server.token()).statusCode()); // evicted
    }

    @Test void anIncompleteReportWithholdsTheXml() throws Exception {
        String sellOnly = "type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency\nSELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR\n";
        JsonNode imported = Json.MAPPER.readTree(post("/api/import", server.token(), "{\"files\":[{\"name\":\"s.csv\",\"contentBase64\":\"" + b64(sellOnly) + "\"}]}").body());
        assertTrue(imported.get("hasErrors").asBoolean());
        JsonNode r = Json.MAPPER.readTree(post("/api/report", server.token(),
                "{\"ledgerId\":\"" + imported.get("ledgerId").asText() + "\",\"year\":2024,\"taxNumber\":\"12345678\",\"allowIncomplete\":false}").body());
        assertFalse(r.get("complete").asBoolean());
        assertTrue(r.get("xmlWithheld").asBoolean());
        assertTrue(absentOrNull(r, "kdvpXml"));
        assertTrue(r.get("auditMarkdown").asText().contains("INCOMPLETE"));
    }

    @Test void describeReturnsTheLayoutNotTheContents() throws Exception {
        JsonNode r = Json.MAPPER.readTree(post("/api/describe", server.token(), "{\"name\":\"s.csv\",\"contentBase64\":\"" + b64(CSV) + "\"}").body());
        String text = r.get("text").asText();
        assertTrue(text.contains("# Layout of s.csv"));
        assertFalse(text.contains("US0378331005"));
    }

    @Test void badInputGetsA400WithAMessage() throws Exception {
        assertEquals(400, post("/api/import", server.token(), "not json").statusCode());
        assertEquals(400, post("/api/import", server.token(), "{\"files\":[]}").statusCode());
        assertEquals(400, post("/api/import", server.token(), "{\"files\":[],\"surprise\":1}").statusCode());
        HttpResponse<String> unknown = post("/api/import", server.token(), "{\"files\":[{\"name\":\"x.csv\",\"contentBase64\":\"" + b64("foo,bar\n1,2\n") + "\"}]}");
        assertEquals(400, unknown.statusCode());
        assertTrue(unknown.body().contains("No adapter recognises x.csv"), unknown.body());
        assertEquals(400, post("/api/import", server.token(), "{\"files\":[{\"name\":\"x.csv\",\"contentBase64\":\"!!!\"}]}").statusCode());
        assertEquals(400, post("/api/report", server.token(), "{\"ledgerId\":null,\"year\":2024,\"taxNumber\":\"12345678\",\"allowIncomplete\":false}").statusCode());
        assertEquals(404, post("/api/nope", server.token(), "{}").statusCode());
        assertEquals(405, get("/api/import", server.token()).statusCode());
    }

    @Test void staticFilesAreServedWithProtectiveHeadersAndNeverOutsideTheWebDirectory() throws Exception {
        HttpResponse<String> index = get("/", null);
        assertEquals(200, index.statusCode());
        assertEquals("<html>ui</html>", index.body());
        assertTrue(index.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertEquals("nosniff", index.headers().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("no-store", index.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("text/javascript; charset=utf-8", get("/app.js", null).headers().firstValue("Content-Type").orElse(""));
        assertEquals(404, get("/missing.js", null).statusCode());
        String traversal = raw("GET /../secret.txt HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nConnection: close\r\n\r\n");
        assertFalse(traversal.contains("TOP SECRET"), traversal);
        String encoded = raw("GET /%2e%2e/secret.txt HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\nConnection: close\r\n\r\n");
        assertFalse(encoded.contains("TOP SECRET"), encoded);
    }

    @Test void withoutABuiltInterfaceTheIndexExplainsHowToBuildIt() throws Exception {
        server.close();
        server = new WebServer(0, dir.resolve("not-built"), dir.resolve("no.env"), Path.of("../../schemas"));
        server.start();
        assertFalse(server.hasUi());
        HttpResponse<String> r = get("/", null);
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("npm --prefix frontend"), r.body());
    }
}
