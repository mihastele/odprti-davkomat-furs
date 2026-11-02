package si.opendavki.cli.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.BrokerAdapters;
import si.opendavki.broker.StatementSource;
import si.opendavki.broker.profile.ProfileAdapter;
import si.opendavki.broker.profile.ProfileLoader;
import si.opendavki.cli.InputException;
import si.opendavki.cli.Services;
import si.opendavki.furs.SchemaChecksums;
import si.opendavki.fx.DotEnv;
import si.opendavki.fx.FxConfig;
import si.opendavki.json.Json;
import si.opendavki.ledger.Ledger;
import si.opendavki.tax.TaxOptions;

/**
 * The local web interface: a static single-page app plus a small JSON API over the same runners the command line uses.
 *
 * <p>Threat model: the page handles personal financial data, so the server listens on the loopback address only; every API
 * call needs a random per-start key (sent in a header, never in a URL the server sees); requests whose {@code Host} or
 * {@code Origin} is not this server are refused (other websites and DNS rebinding cannot talk to it); bodies are size
 * limited; nothing is written to disk and nothing is logged beyond errors without data.
 */
public final class WebServer implements AutoCloseable {
    public static final String TOKEN_HEADER = "X-Opendavki-Token";
    private static final long MAX_BODY_BYTES = 64L * 1024 * 1024;

    private final HttpServer server;
    private final String token;
    private final Path webDir;
    private final Path envFile;
    private final Path schemaDir;
    /** Imported ledgers by id, newest last; bounded so a long session cannot grow without limit. Never written to disk. */
    private final Map<String, Ledger> ledgers = new java.util.LinkedHashMap<>();
    private static final int MAX_LEDGERS = 5;

    public WebServer(int port, Path webDir, Path envFile, Path schemaDir) throws IOException {
        this.webDir = webDir;
        this.envFile = envFile;
        this.schemaDir = schemaDir;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", this::api);
        server.createContext("/", this::staticFile);
        server.setExecutor(Executors.newFixedThreadPool(4));
    }

    public void start() { server.start(); }

    @Override public void close() { server.stop(0); }

    public int port() { return server.getAddress().getPort(); }

    public String token() { return token; }

    /** The address to open; the key is in the fragment, which browsers never send to the server. */
    public String url() { return "http://127.0.0.1:" + port() + "/#token=" + token; }

    public boolean hasUi() { return Files.isRegularFile(webDir.resolve("index.html")); }

    // ---- request guards ---------------------------------------------------------------------------------------

    private boolean hostAllowed(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        String own = ":" + port();
        if (host == null || !(host.equals("127.0.0.1" + own) || host.equals("localhost" + own))) return false;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return origin == null || origin.equals("http://127.0.0.1" + own) || origin.equals("http://localhost" + own);
    }

    private boolean tokenOk(HttpExchange ex) {
        String given = ex.getRequestHeaders().getFirst(TOKEN_HEADER);
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static void commonHeaders(Headers h) {
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'");
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        Headers h = ex.getResponseHeaders();
        commonHeaders(h);
        h.set("Content-Type", contentType);
        ex.sendResponseHeaders(status, body.length == 0 && !ex.getRequestMethod().equals("HEAD") ? -1 : body.length);
        if (body.length > 0) try (var out = ex.getResponseBody()) { out.write(body); }
        ex.close();
    }

    private static void json(HttpExchange ex, int status, Object payload) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.MAPPER.writeValueAsBytes(payload));
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        json(ex, status, Map.of("error", message));
    }

    private static byte[] readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BODY_BYTES) throw new InputException("The upload is larger than " + MAX_BODY_BYTES / (1024 * 1024) + " MB");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    // ---- API --------------------------------------------------------------------------------------------------

    private void api(HttpExchange ex) throws IOException {
        try {
            if (!hostAllowed(ex)) { error(ex, 403, "Forbidden host or origin"); return; }
            if (!tokenOk(ex)) { error(ex, 401, "Missing or wrong key. Open the address printed by `serve`, including the part after #."); return; }
            String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
            if (method.equals("GET") && path.equals("/api/info")) { json(ex, 200, info()); return; }
            if (method.equals("GET") && path.startsWith("/api/ledger/") && path.endsWith("/download")) { downloadLedger(ex, path); return; }
            if (!method.equals("POST")) { error(ex, 405, "Method not allowed"); return; }
            byte[] body = readBody(ex.getRequestBody());
            switch (path) {
                case "/api/import" -> json(ex, 200, importStatements(Json.MAPPER.readValue(body, Requests.Import.class)));
                case "/api/ledger" -> json(ex, 200, loadLedger(Json.MAPPER.readValue(body, Requests.LoadLedger.class)));
                case "/api/describe" -> json(ex, 200, Map.of("text", describe(Json.MAPPER.readValue(body, Requests.Describe.class))));
                case "/api/report" -> json(ex, 200, report(Json.MAPPER.readValue(body, Requests.Report.class)));
                default -> error(ex, 404, "Unknown endpoint");
            }
        } catch (InputException | IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
            error(ex, 400, e.getMessage() == null ? "Invalid request" : e.getMessage().lines().findFirst().orElse("Invalid request"));
        } catch (Exception e) {
            System.err.println("Internal error handling " + ex.getRequestURI().getPath() + ": " + e.getClass().getName()); // no data in the log
            error(ex, 500, "Internal error");
        }
    }

    private Map<String, Object> info() throws IOException {
        Map<String, String> env = DotEnv.environment(envFile);
        Map<String, Object> settings = new java.util.LinkedHashMap<>();
        try {
            FxConfig fx = FxConfig.from(env);
            settings.put("fxSource", fx.source().name());
            settings.put("missingDay", fx.missingDay().name());
            settings.put("includeFees", TaxOptions.from(env).includeFees());
            settings.put("timezone", env.getOrDefault("OPENDAVKI_TIMEZONE", "UTC"));
            settings.put("instrumentsFile", !env.getOrDefault("OPENDAVKI_INSTRUMENTS_FILE", "").isBlank());
            settings.put("overridesFile", !env.getOrDefault("OPENDAVKI_OVERRIDES_FILE", "").isBlank());
        } catch (IllegalArgumentException e) {
            settings.put("error", e.getMessage());
        }
        List<Map<String, String>> schemas = new ArrayList<>();
        try {
            SchemaChecksums.verify(schemaDir).forEach(e -> schemas.add(Map.of("name", e.name(), "status", e.status().name())));
        } catch (IOException e) {
            schemas.add(Map.of("name", "schemas", "status", "UNVERIFIED: " + e.getMessage()));
        }
        List<Map<String, String>> adapters = BrokerAdapters.all().stream().map(a -> Map.of("id", a.id(), "name", a.displayName())).toList();
        return Map.of("version", "0.1.0", "adapters", adapters, "settings", settings, "schemas", schemas);
    }

    private Map<String, Object> importStatements(Requests.Import req) throws IOException {
        if (req.files() == null || req.files().isEmpty()) throw new InputException("Choose at least one statement file");
        List<BrokerAdapter> extra = new ArrayList<>();
        if (req.profiles() != null) {
            for (Requests.Upload p : req.profiles()) extra.add(new ProfileAdapter(ProfileLoader.parse(p.text(), p.name())));
        }
        List<StatementSource> sources = new ArrayList<>();
        for (Requests.File f : req.files()) sources.add(new StatementSource(f.name(), decode(f)));
        var outcome = Services.runImport(sources, req.adapter(), extra, DotEnv.environment(envFile));
        Map<String, Object> out = new java.util.LinkedHashMap<>(ledgerSummary(outcome.ledger()));
        out.put("files", outcome.files());
        out.put("diagnostics", outcome.diagnostics());
        out.put("hasErrors", outcome.hasErrors());
        return out;
    }

    private Map<String, Object> loadLedger(Requests.LoadLedger req) throws IOException {
        if (req.text() == null || req.text().isBlank()) throw new InputException("The ledger file is empty");
        Ledger ledger = Json.MAPPER.readValue(req.text(), Ledger.class);
        Map<String, Object> out = new java.util.LinkedHashMap<>(ledgerSummary(ledger));
        out.put("diagnostics", si.opendavki.ledger.LedgerChecker.check(ledger));
        return out;
    }

    /** Stores the ledger and returns what the page needs to show it, with every amount as text (never as a JSON number). */
    private Map<String, Object> ledgerSummary(Ledger ledger) {
        String id = java.util.UUID.randomUUID().toString();
        synchronized (ledgers) {
            ledgers.put(id, ledger);
            while (ledgers.size() > MAX_LEDGERS) ledgers.remove(ledgers.keySet().iterator().next());
        }
        List<Map<String, String>> entries = new ArrayList<>();
        for (si.opendavki.ledger.LedgerEntry e : ledger.entries()) entries.add(describeEntry(e));
        return Map.of("ledgerId", id, "entries", entries);
    }

    private static Map<String, String> describeEntry(si.opendavki.ledger.LedgerEntry e) {
        String what = switch (e) {
            case si.opendavki.ledger.Trade t -> t.side() + " " + plain(t.quantity()) + " @ " + plain(t.price()) + " " + t.currency()
                    + (t.fee().signum() > 0 ? " (fee " + plain(t.fee()) + " " + t.feeCurrency() + ")" : "");
            case si.opendavki.ledger.DividendPayment d -> "dividend " + plain(d.gross()) + " " + d.currency()
                    + (d.withholdingTax().signum() > 0 ? ", tax withheld " + plain(d.withholdingTax()) : "");
            case si.opendavki.ledger.SecurityTransfer s -> "transfer " + s.direction() + " " + plain(s.quantity())
                    + (s.counterparty() == null ? "" : " (" + s.counterparty() + ")");
            case si.opendavki.ledger.CorporateAction c -> c.type() + (c.newQuantity() == null ? "" : " " + plain(c.newQuantity()) + ":" + plain(c.oldQuantity()))
                    + (c.target() == null ? "" : " into " + c.target().key()) + (c.description() == null || c.description().isBlank() ? "" : " - " + c.description());
        };
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("date", e.date().toString());
        m.put("broker", e.broker());
        m.put("instrument", e.instrument().key());
        m.put("name", e.instrument().symbol() == null ? "" : e.instrument().symbol());
        m.put("what", what);
        m.put("source", e.source() == null ? "" : e.source().toString());
        return m;
    }

    private static String plain(java.math.BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }

    private Ledger ledger(String id) {
        synchronized (ledgers) {
            Ledger l = id == null ? null : ledgers.get(id);
            if (l == null) throw new InputException("Import statements first: there is no ledger (or the server was restarted)");
            return l;
        }
    }

    private void downloadLedger(HttpExchange ex, String path) throws IOException {
        String id = path.substring("/api/ledger/".length(), path.length() - "/download".length());
        byte[] body = (Json.MAPPER.writeValueAsString(ledger(id)) + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"ledger.json\"");
        send(ex, 200, "application/json; charset=utf-8", body);
    }

    private String describe(Requests.Describe req) throws IOException {
        return Services.describe(new StatementSource(req.name(), decode(req.name(), req.contentBase64())), req.keep() == null ? List.of() : req.keep());
    }

    private Map<String, Object> report(Requests.Report req) throws IOException {
        Ledger ledger = ledger(req.ledgerId());
        var o = Services.runReport(ledger, req.year(), req.taxNumber(), DotEnv.environment(envFile), schemaDir, req.allowIncomplete());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("complete", o.result().complete());
        out.put("xmlWithheld", o.xmlWithheld());
        out.put("draftData", o.result().diagnostics().stream().anyMatch(d -> d.code().equals("DRAFT_SOURCE_USED")));
        out.put("diagnostics", o.result().diagnostics());
        out.put("gains", o.gains());
        out.put("kdvpXml", o.kdvpXml());
        out.put("divXml", o.divXml());
        out.put("kdvpValidation", o.kdvpValidation());
        out.put("divValidation", o.divValidation());
        out.put("auditMarkdown", o.auditMarkdown());
        out.put("auditJson", o.auditJson());
        return out;
    }

    private static byte[] decode(Requests.File f) {
        return decode(f.name(), f.contentBase64());
    }

    private static byte[] decode(String name, String base64) {
        if (name == null || name.isBlank()) throw new InputException("A file has no name");
        if (base64 == null) throw new InputException(name + " has no content");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new InputException(name + " could not be decoded");
        }
    }

    // ---- static files -----------------------------------------------------------------------------------------

    private void staticFile(HttpExchange ex) throws IOException {
        try {
            if (!hostAllowed(ex)) { error(ex, 403, "Forbidden host or origin"); return; }
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) { error(ex, 405, "Method not allowed"); return; }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";
            Path root = webDir.toAbsolutePath().normalize();
            Path file = root.resolve(path.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                if (path.equals("/index.html")) {
                    send(ex, 404, "text/plain; charset=utf-8", ("The web interface is not built. Run: npm --prefix frontend ci && npm --prefix frontend run build\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    error(ex, 404, "Not found");
                }
                return;
            }
            send(ex, 200, contentType(file.getFileName().toString()), ex.getRequestMethod().equals("HEAD") ? new byte[0] : Files.readAllBytes(file));
        } catch (Exception e) {
            System.err.println("Internal error serving a file: " + e.getClass().getName());
            error(ex, 500, "Internal error");
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".json")) return "application/json; charset=utf-8";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
