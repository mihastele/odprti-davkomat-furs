package si.opendavki.cli;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import si.opendavki.app.AppPaths;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.BrokerAdapters;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.furs.SchemaChecksums;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.ledger.Ledger;
import si.opendavki.ledger.LedgerChecker;
import si.opendavki.furs.DivXmlWriter;
import si.opendavki.furs.FormType;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.fx.DotEnv;
import si.opendavki.fx.FxConfig;
import si.opendavki.model.DivReport;
import si.opendavki.model.KdvpReport;

@Command(name = "opendavki", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Open-source, local-first FURS eDavki XML tooling. With no command, starts the local web interface.",
        subcommands = {Main.Generate.class, Main.Validate.class, Main.Schemas.class, Main.Adapters.class,
                Main.Import.class, Main.Check.class, Main.Fx.class, Main.Report.class, Main.Audit.class, Main.Describe.class,
                Main.Profiles.class, Main.Serve.class, Main.Paths.class, Main.Doctor.class})
public final class Main implements Callable<Integer> {

    /** Exit codes: 0 ok, 1 XSD validation failed, 2 usage/input error, 3 schema missing. */
    static final int VALIDATION_FAILED = 1, INPUT_ERROR = 2, SCHEMA_MISSING = 3;

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /** Command line with short messages for the failures a user causes (missing or unreadable files). */
    static CommandLine commandLine() {
        CommandLine cmd = new CommandLine(new Main());
        cmd.setExecutionExceptionHandler((e, c, parse) -> {
            if (e instanceof java.nio.file.NoSuchFileException || e instanceof java.io.FileNotFoundException) {
                System.err.println("File not found: " + e.getMessage());
                return INPUT_ERROR;
            }
            if (e instanceof SchemaValidator.SchemaMissingException) {
                System.err.println(e.getMessage());
                return SCHEMA_MISSING;
            }
            if (e instanceof IOException || e instanceof InputException) {
                System.err.println("Error: " + e.getMessage());
                return INPUT_ERROR;
            }
            throw e;
        });
        return cmd;
    }

    @Override public Integer call() {
        // Starting with no arguments is the packaged application: start the local UI and open it. `--help` still works
        // because picocli handles it before this method, and `serve` remains available for terminals and scripts.
        try {
            return Serve.run(0, null, null, null, true, true);
        } catch (SchemaValidator.SchemaMissingException e) {
            System.err.println(e.getMessage());
            return SCHEMA_MISSING;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            return INPUT_ERROR;
        }
    }

    @Command(name = "generate", description = "Generate XML from a JSON input file and validate it against the XSD.")
    static final class Generate implements Callable<Integer> {
        @Parameters(index = "0", description = "Form: KDVP, DIV or OBR") FormType form;
        @Parameters(index = "1", description = "Input JSON file (synthetic or normalized ledger export)") Path input;
        @Option(names = {"-o", "--out"}, description = "Output XML file (default: stdout)") Path out;
        @Option(names = "--schema-dir", description = "Directory with official XSDs (default: ./schemas, then the bundled copy)") Path schemaDir;
        @Option(names = "--skip-validation", description = "Write XML without XSD validation (NOT for filing)") boolean skip;

        @Override public Integer call() throws IOException {
            String xml;
            try {
                xml = switch (form) {
                    case KDVP -> new KdvpXmlWriter().write(si.opendavki.json.Json.MAPPER.readValue(input.toFile(), KdvpReport.class));
                    case DIV -> new DivXmlWriter().write(si.opendavki.json.Json.MAPPER.readValue(input.toFile(), DivReport.class));
                    case OBR -> new si.opendavki.furs.ObrXmlWriter().write(si.opendavki.json.Json.MAPPER.readValue(input.toFile(), si.opendavki.model.ObrReport.class));
                };
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                System.err.println("Input error: " + e.getMessage());
                return INPUT_ERROR;
            }
            if (out != null) Files.writeString(out, xml, StandardCharsets.UTF_8); else System.out.println(xml);
            if (skip) {
                System.err.println("WARNING: XSD validation skipped.");
                return 0;
            }
            return report(form, xml, CliPaths.schemaDir(schemaDir), out);
        }
    }

    @Command(name = "validate", description = "Validate an existing XML file against the official XSD.")
    static final class Validate implements Callable<Integer> {
        @Parameters(index = "0", description = "Form: KDVP, DIV or OBR") FormType form;
        @Parameters(index = "1", description = "XML file") Path xmlFile;
        @Option(names = "--schema-dir", description = "Directory with official XSDs (default: ./schemas, then the bundled copy)") Path schemaDir;

        @Override public Integer call() throws IOException {
            return report(form, Files.readString(xmlFile, StandardCharsets.UTF_8), CliPaths.schemaDir(schemaDir), xmlFile);
        }
    }

    @Command(name = "adapters", description = "List the broker adapters found on the classpath.")
    static final class Adapters implements Callable<Integer> {
        @Override public Integer call() {
            BrokerAdapters.all().forEach(a -> System.out.printf("%-12s %s%n", a.id(), a.displayName()));
            return 0;
        }
    }

    @Command(name = "import", description = "Read broker statements into one canonical ledger (JSON), de-duplicate and check it.")
    static final class Import implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "Statement files") List<Path> statements;
        @Option(names = "--adapter", description = "Adapter id for all files (default: auto-detect per file)") String adapterId;
        @Option(names = {"-o", "--out"}, required = true, description = "Ledger JSON file to write") Path out;
        @Option(names = "--env-file", description = "Settings file (default: ./.env, then the user config directory)") Path envFile;
        @Option(names = "--strict", description = "Do not write the ledger if any ERROR was found") boolean strict;
        @Option(names = "--profile", description = "Profile JSON describing a CSV layout (repeatable); see docs/profiles.md") List<Path> profiles = new ArrayList<>();

        @Override public Integer call() throws IOException {
            List<BrokerAdapter> extra = new ArrayList<>();
            for (Path profile : profiles) {
                try {
                    extra.add(new si.opendavki.broker.profile.ProfileAdapter(si.opendavki.broker.profile.ProfileLoader.load(profile)));
                } catch (IllegalArgumentException e) {
                    System.err.println("Profile error: " + e.getMessage());
                    return INPUT_ERROR;
                }
            }
            List<StatementSource> sources = new ArrayList<>();
            for (Path p : statements) sources.add(StatementSource.read(p));
            ImportRunner.Outcome outcome;
            try {
                outcome = ImportRunner.run(sources, adapterId, extra, CliPaths.environment(envFile));
            } catch (InputException e) {
                System.err.println(e.getMessage());
                return INPUT_ERROR;
            }
            for (ImportRunner.FileSummary f : outcome.files()) {
                System.err.printf("%s [%s]: %d rows read, %d mapped, %d skipped, %d failed%s%n", f.file(), f.adapter(), f.rowsRead(), f.rowsMapped(),
                        f.rowsSkipped(), f.rowsFailed(), f.first() == null ? "" : ", entries from " + f.first() + " to " + f.last());
            }
            Ledger ledger = outcome.ledger();
            if (strict && outcome.hasErrors()) {
                printDiagnostics(outcome.diagnostics(), "NOT written (--strict): " + ledger.entries().size() + " ledger entries");
                return VALIDATION_FAILED;
            }
            Files.writeString(out, si.opendavki.json.Json.MAPPER.writeValueAsString(ledger) + "\n", StandardCharsets.UTF_8);
            return printDiagnostics(outcome.diagnostics(), ledger.entries().size() + " ledger entries written to " + out);
        }
    }

    @Command(name = "check", description = "Run consistency checks on a ledger JSON file.")
    static final class Check implements Callable<Integer> {
        @Parameters(index = "0", description = "Ledger JSON file") Path ledgerFile;
        @Override public Integer call() throws IOException {
            Ledger l = si.opendavki.json.Json.MAPPER.readValue(ledgerFile.toFile(), Ledger.class);
            return printDiagnostics(LedgerChecker.check(l), l.entries().size() + " ledger entries checked");
        }
    }

    @Command(name = "report", description = "Compute Doh-KDVP and Doh-Div for a tax year from a ledger, write validated XML and an audit trail.")
    static final class Report implements Callable<Integer> {
        @Parameters(index = "0", description = "Ledger JSON file (from `import`)") Path ledgerFile;
        @Option(names = "--year", required = true, description = "Tax year") int year;
        @Option(names = "--tax-number", required = true, description = "Your 8-digit tax number (davčna številka)") String taxNumber;
        @Option(names = "--out-dir", defaultValue = ".", description = "Where to write kdvp-<year>.xml, div-<year>.xml, obr-<year>.xml, audit-<year>.json and .md") Path outDir;
        @Option(names = "--env-file", description = "Settings file (default: ./.env, then the user config directory)") Path envFile;
        @Option(names = "--schema-dir", description = "Directory with official XSDs (default: ./schemas, then the bundled copy)") Path schemaDir;
        @Option(names = "--allow-incomplete", description = "Write XML even if some instruments/payments were left out because of errors (NOT for filing)") boolean allowIncomplete;

        @Override public Integer call() throws IOException {
            Ledger ledger;
            ReportRunner.Output o;
            try {
                ledger = si.opendavki.json.Json.MAPPER.readValue(ledgerFile.toFile(), Ledger.class);
                o = ReportRunner.run(ledger, year, taxNumber, CliPaths.environment(envFile), CliPaths.schemaDir(schemaDir), allowIncomplete);
            } catch (SchemaValidator.SchemaMissingException e) {
                System.err.println(e.getMessage());
                return SCHEMA_MISSING;
            } catch (InputException | IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                System.err.println("Input error: " + e.getMessage());
                return INPUT_ERROR;
            }
            Files.createDirectories(outDir);
            Path audit = outDir.resolve("audit-" + year + ".json");
            Files.writeString(audit, o.auditJson(), StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("audit-" + year + ".md"), o.auditMarkdown(), StandardCharsets.UTF_8);
            o.gains().forEach(g -> System.err.printf("  gain %-14s %s EUR%n", g.instrument(), g.gainEur().toPlainString()));
            int exit = printDiagnostics(o.result().diagnostics(), "Audit trail written to " + audit + " and audit-" + year + ".md ("
                    + o.result().lotMatches().size() + " lot matches, " + o.result().rates().size() + " rates)");
            if (o.xmlWithheld()) {
                System.err.println("REFUSING to write XML: the result is incomplete. Fix the errors above, or use --allow-incomplete to inspect partial output.");
                return VALIDATION_FAILED;
            }
            if (!o.result().complete()) System.err.println("WARNING: incomplete result written because of --allow-incomplete. Do not file it.");
            if (o.kdvpXml() == null && o.divXml() == null && o.obrXml() == null) {
                System.err.println("Nothing to declare for " + year + ".");
                return exit;
            }
            if (o.kdvpXml() != null) exit = Math.max(exit, write(FormType.KDVP, o.kdvpXml(), o.kdvpValidation(), outDir.resolve("kdvp-" + year + ".xml")));
            if (o.divXml() != null) exit = Math.max(exit, write(FormType.DIV, o.divXml(), o.divValidation(), outDir.resolve("div-" + year + ".xml")));
            if (o.obrXml() != null) exit = Math.max(exit, write(FormType.OBR, o.obrXml(), o.obrValidation(), outDir.resolve("obr-" + year + ".xml")));
            return exit;
        }

        private static int write(FormType form, String xml, ReportRunner.Validation v, Path file) throws IOException {
            Files.writeString(file, xml, StandardCharsets.UTF_8);
            if (!v.schemaAvailable()) { System.err.println(v.message()); return SCHEMA_MISSING; }
            v.issues().forEach(i -> System.err.println("  " + i));
            if (v.valid()) { System.err.println("VALID against " + form.xsdFileName + " (" + file + ")"); return 0; }
            System.err.println("INVALID against " + form.xsdFileName + ": " + v.issues().size() + " issue(s)");
            return VALIDATION_FAILED;
        }
    }

    @Command(name = "describe", description = "Summarise a CSV statement's layout (column names, value shapes) without its contents; safe to share after you read it.")
    static final class Describe implements Callable<Integer> {
        @Parameters(index = "0", description = "Statement file") Path file;
        @Option(names = {"--values", "--keep"}, split = ",", description = "CSV: also list the distinct values of these columns (by header name). PDF: also show these words unmasked") List<String> valueColumns = new ArrayList<>();
        @Option(names = "--profile", description = "Check the file against this profile and say what is missing") Path profile;
        @Option(names = {"-o", "--out"}, description = "Write the description to a file instead of stdout") Path out;

        @Override public Integer call() throws IOException {
            StatementSource src = StatementSource.read(file);
            boolean csv = !PdfDescriber.isPdf(src.content()) && !si.opendavki.broker.XlsxSheets.isXlsx(src.content());
            if (!csv && profile != null) { System.err.println("--profile applies to CSV files only."); return INPUT_ERROR; }
            if (!csv) {
                String described = DescribeRunner.describe(src, valueColumns);
                if (out != null) Files.writeString(out, described, StandardCharsets.UTF_8); else System.out.print(described);
                return 0;
            }
            StringBuilder md = new StringBuilder(DescribeRunner.describe(src, valueColumns));
            int exit = 0;
            if (profile != null) {
                try {
                    si.opendavki.broker.profile.ProfileAdapter a = new si.opendavki.broker.profile.ProfileAdapter(si.opendavki.broker.profile.ProfileLoader.load(profile));
                    boolean ok = a.accepts(src);
                    md.append("## Profile `").append(a.id()).append("`\n\n").append(ok ? "The header contains every required column.\n" : "The header does NOT contain every required column.\n");
                    if (!ok) {
                        ImportResult r = a.read(src);
                        r.diagnostics().forEach(d -> md.append("- ").append(d.message()).append('\n'));
                        exit = VALIDATION_FAILED;
                    }
                } catch (IllegalArgumentException e) {
                    System.err.println("Profile error: " + e.getMessage());
                    return INPUT_ERROR;
                }
            }
            if (out != null) Files.writeString(out, md.toString(), StandardCharsets.UTF_8); else System.out.print(md);
            return exit;
        }
    }

    @Command(name = "serve", mixinStandardHelpOptions = true, description = "Start the local web interface (this computer only; nothing leaves it).")
    static final class Serve implements Callable<Integer> {
        @Option(names = "--port", defaultValue = "0", description = "Port on 127.0.0.1 (0 = pick a free one)") int port;
        @Option(names = "--web-dir", description = "Override the built web interface (default: bundled in the application)") Path webDir;
        @Option(names = "--env-file", description = "Settings file (default: ./.env, then the user config directory)") Path envFile;
        @Option(names = "--schema-dir", description = "Directory with official XSDs (default: ./schemas, then the bundled copy)") Path schemaDir;
        @Option(names = "--open", negatable = true, description = "Open the address in the default browser") boolean open;
        @Option(names = "--tray", description = "Show a tray icon with Open and Quit (if the desktop supports it)") boolean tray;

        @Override public Integer call() throws Exception {
            return run(port, webDir, envFile, schemaDir, open, tray);
        }

        /** Shared by {@code serve} and the no-argument packaged launch. */
        static Integer run(int port, Path webDirOverride, Path envFileOverride, Path schemaDirOverride,
                           boolean shouldOpen, boolean trayIcon) throws Exception {
            Path settings = CliPaths.envFile(envFileOverride);
            Path schemas = CliPaths.schemaDir(schemaDirOverride);
            Path web = webDirOverride == null ? null : webDirOverride.toAbsolutePath().normalize();
            si.opendavki.cli.web.WebServer server = new si.opendavki.cli.web.WebServer(port, web, settings, schemas);
            server.start();
            CountDownLatch stopped = new CountDownLatch(1);
            Tray tray = null;
            try {
                System.err.println("OpenDavki web interface (this computer only).");
                System.err.println("  Settings: " + settings + (Files.isRegularFile(settings) ? "" : " (not created; defaults are used)"));
                System.err.println("  Schemas:  " + schemas);
                System.err.println("  Web UI:   " + (server.hasUi() ? (web == null ? "bundled in the application" : web.toString()) : "NOT FOUND"));
                System.err.println("\nOpen:\n\n    " + server.url() + "\n\nThe address contains a one-time key; keep it private.");
                if (!server.hasUi()) {
                    System.err.println("NOTE: the web interface is not built. Run: npm --prefix frontend ci && npm --prefix frontend run build");
                }
                if (shouldOpen) openBrowser(server.url());
                if (trayIcon) {
                    tray = Tray.install(() -> openBrowser(server.url()), () -> {
                        server.close();
                        stopped.countDown();
                    });
                    if (tray == null) System.err.println("NOTE: this desktop has no system tray; press Ctrl-C to stop.");
                }
                Runtime.getRuntime().addShutdownHook(new Thread(server::close));
                try {
                    stopped.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 0;
            } finally {
                if (tray != null) tray.close();
                server.close();
            }
        }

        static void openBrowser(String url) {
            try {
                if (!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                }
            } catch (Exception e) {
                // fall through to the printed address: a missing browser must not stop the local server
            }
            System.err.println("Open this address in a browser: " + url);
        }
    }

    @Command(name = "paths", mixinStandardHelpOptions = true, description = "Show the settings, cache, schema and web paths this installation uses.")
    static final class Paths implements Callable<Integer> {
        @Option(names = "--web-dir", description = "Override the built web interface") Path webDir;
        @Option(names = "--env-file", description = "Settings file override") Path envFile;
        @Option(names = "--schema-dir", description = "Schema directory override") Path schemaDir;

        @Override public Integer call() throws Exception {
            AppPaths app = AppPaths.current();
            Path settings = CliPaths.envFile(envFile);
            Path schemas = CliPaths.schemaDir(schemaDir);
            System.out.println("home       " + app.home());
            System.out.println("config     " + app.configDir());
            System.out.println("cache      " + app.cacheDir());
            System.out.println("settings   " + settings + (Files.isRegularFile(settings) ? "" : " (not created)"));
            System.out.println("schemas    " + schemas);
            System.out.println("fx cache   " + app.fxCacheDir());
            System.out.println("web ui     " + (webDir != null ? webDir.toAbsolutePath().normalize() : "bundled in the application"));
            return 0;
        }
    }

    @Command(name = "doctor", mixinStandardHelpOptions = true, description = "Check Java, settings, schemas, the web interface and the data directories.")
    static final class Doctor implements Callable<Integer> {
        @Option(names = "--web-dir", description = "Override the built web interface") Path webDir;
        @Option(names = "--env-file", description = "Settings file override") Path envFile;
        @Option(names = "--schema-dir", description = "Schema directory override") Path schemaDir;

        @Override public Integer call() throws Exception {
            int problems = 0;
            System.out.println("OpenDavki doctor");
            System.out.println("  Java       " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("  OS         " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
            AppPaths app = AppPaths.current();
            System.out.println("  Home       " + app.home());
            System.out.println("  Config     " + app.configDir());
            System.out.println("  Cache      " + app.cacheDir());

            Path settings = null;
            Map<String, String> environment = Map.of();
            try {
                settings = CliPaths.envFile(envFile);
                environment = DotEnv.environment(settings);
                System.out.println("  Settings   " + settings + (Files.isRegularFile(settings) ? "" : " (not created; defaults are used)"));
            } catch (IOException | RuntimeException e) {
                System.out.println("  Settings   PROBLEM: " + e.getMessage());
                problems++;
            }

            try {
                Path schemas = CliPaths.schemaDir(schemaDir);
                System.out.println("  Schemas    " + schemas);
                long bad = SchemaChecksums.verify(schemas).stream().filter(e -> e.status() != SchemaChecksums.Status.OK).count();
                if (bad > 0) { System.out.println("             PROBLEM: " + bad + " schema file(s) do not match SHA256SUMS"); problems++; }
            } catch (Exception e) {
                System.out.println("  Schemas    PROBLEM: " + e.getMessage());
                problems++;
            }

            boolean web = webDir != null ? Files.isRegularFile(webDir.resolve("index.html")) : si.opendavki.cli.web.WebServer.bundledUiAvailable();
            System.out.println("  Web UI     " + (web ? (webDir != null ? webDir.toAbsolutePath().normalize() : "bundled in the application") : "NOT FOUND"));
            if (!web) problems++;

            try {
                FxConfig.from(environment);
                System.out.println("  FX         " + environment.getOrDefault("OPENDAVKI_FX_SOURCE", "ECB") + " (settings parse)");
            } catch (IllegalArgumentException e) {
                System.out.println("  FX         PROBLEM: " + e.getMessage());
                problems++;
            }

            for (Path dir : List.of(app.configDir(), app.cacheDir())) {
                try {
                    Files.createDirectories(dir);
                    if (!Files.isWritable(dir)) { System.out.println("  Writable   PROBLEM: " + dir); problems++; }
                } catch (IOException e) {
                    System.out.println("  Writable   PROBLEM: " + dir + " (" + e.getMessage() + ")");
                    problems++;
                }
            }

            System.out.println("  Adapters   " + BrokerAdapters.all().size());
            if (problems == 0) System.out.println("\nEverything looks ready.");
            else System.out.println("\n" + problems + " problem(s) found.");
            return problems == 0 ? 0 : INPUT_ERROR;
        }
    }

    @Command(name = "profiles", description = "Validate profile files and show what each expects.")
    static final class Profiles implements Callable<Integer> {
        @Parameters(arity = "1..*", description = "Profile JSON files") List<Path> files;

        @Override public Integer call() {
            int bad = 0;
            for (Path f : files) {
                try {
                    si.opendavki.broker.profile.Profile p = si.opendavki.broker.profile.ProfileLoader.load(f);
                    System.out.printf("%-28s %-8s %s%n  requires columns: %s%n", p.id(), p.status(), p.displayName(), p.requiredColumns());
                    if (p.note() != null) System.out.println("  note: " + p.note());
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("INVALID " + e.getMessage());
                    bad++;
                }
            }
            return bad == 0 ? 0 : INPUT_ERROR;
        }
    }

    @Command(name = "audit", description = "Render an audit-<year>.json as a readable Markdown report.")
    static final class Audit implements Callable<Integer> {
        @Parameters(index = "0", description = "audit-<year>.json written by `report`") Path auditFile;
        @Option(names = {"-o", "--out"}, description = "Output file (default: stdout)") Path out;

        @Override public Integer call() throws IOException {
            si.opendavki.tax.TaxResult.Audit a;
            try {
                a = si.opendavki.json.Json.MAPPER.readValue(auditFile.toFile(), si.opendavki.tax.TaxResult.Audit.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                System.err.println("Input error: " + e.getMessage());
                return INPUT_ERROR;
            }
            String md = si.opendavki.tax.AuditReport.markdown(a);
            if (out != null) Files.writeString(out, md, StandardCharsets.UTF_8); else System.out.print(md);
            return a.complete() ? 0 : VALIDATION_FAILED;
        }
    }

    @Command(name = "fx", description = "Look up the EUR conversion rate that the configured source gives for a day.")
    static final class Fx implements Callable<Integer> {
        @Parameters(index = "0", description = "ISO 4217 currency, e.g. USD") String currency;
        @Parameters(index = "1", description = "Date yyyy-MM-dd") java.time.LocalDate date;
        @Parameters(index = "2", arity = "0..1", description = "Optional amount to convert to EUR") java.math.BigDecimal amount;
        @Option(names = "--env-file", description = "Settings file (default: ./.env, then the user config directory)") Path envFile;

        @Override public Integer call() throws IOException {
            FxConfig cfg;
            try {
                cfg = FxConfig.from(CliPaths.environment(envFile));
            } catch (IllegalArgumentException e) {
                System.err.println("Configuration error: " + e.getMessage());
                return INPUT_ERROR;
            }
            try {
                si.opendavki.fx.Rate r = new si.opendavki.fx.FxService(cfg).rate(currency.toUpperCase(java.util.Locale.ROOT), date);
                System.out.printf("source=%s currency=%s requested=%s rateDate=%s unitsPerEur=%s%s%n", r.source(), r.currency(),
                        r.requestedDate(), r.rateDate(), r.unitsPerEur().toPlainString(), r.isFallback() ? " (fallback to earlier day)" : "");
                if (amount != null) System.out.println(amount.toPlainString() + " " + r.currency() + " = " + r.toEur(amount).toPlainString() + " EUR");
                return 0;
            } catch (si.opendavki.fx.RateUnavailableException e) {
                System.err.println(e.getMessage());
                return SCHEMA_MISSING;
            }
        }
    }

    /** Prints diagnostics (INFO hidden unless there is nothing else) and returns 1 if any is an ERROR. */
    static int printDiagnostics(List<Diagnostic> diags, String summary) {
        diags.stream().filter(d -> d.severity() != Diagnostic.Severity.INFO).forEach(d -> System.err.println("  " + d));
        long infos = diags.stream().filter(d -> d.severity() == Diagnostic.Severity.INFO).count();
        long errors = diags.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).count();
        long warns = diags.stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
        System.err.println(summary + ": " + errors + " error(s), " + warns + " warning(s), " + infos + " info");
        return errors > 0 ? VALIDATION_FAILED : 0;
    }

    @Command(name = "schemas", description = "Manage the XSD directory.", subcommands = {Schemas.Status.class, Schemas.Fetch.class})
    static final class Schemas implements Runnable {
        @Override public void run() { CommandLine.usage(this, System.out); }

        @Command(name = "status", description = "Verify the XSDs against SHA256SUMS and show which required ones are present.")
        static final class Status implements Callable<Integer> {
            @Option(names = "--schema-dir") Path schemaDir;
            @Override public Integer call() throws Exception {
                Path dir = CliPaths.schemaDir(schemaDir);
                System.out.println("Schema directory: " + dir);
                int problems = 0;
                for (FormType f : FormType.values()) {
                    if (!Files.isRegularFile(dir.resolve(f.xsdFileName))) { System.out.println("MISSING   " + f.xsdFileName); problems++; }
                }
                try {
                    for (SchemaChecksums.Entry e : SchemaChecksums.verify(dir)) {
                        System.out.printf("%-9s %s  sha256=%s%s%n", e.status(), e.name(), e.actual() != null ? e.actual() : e.expected(),
                                e.status() == SchemaChecksums.Status.MISMATCH ? "  (expected " + e.expected() + ")" : "");
                        if (e.status() != SchemaChecksums.Status.OK) problems++;
                    }
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                    problems++;
                }
                return problems == 0 ? 0 : SCHEMA_MISSING;
            }
        }

        @Command(name = "fetch", description = "Download an XSD from an explicit URL (copy the link from the official FURS eDavki page).")
        static final class Fetch implements Callable<Integer> {
            @Parameters(index = "0", description = "Name to save as, e.g. Doh_KDVP_9.xsd") String fileName;
            @Parameters(index = "1", description = "Source URL") URI url;
            @Option(names = "--schema-dir") Path schemaDir;
            @Override public Integer call() throws Exception {
                Path dir = CliPaths.schemaDir(schemaDir);
                if (!"https".equals(url.getScheme())) { System.err.println("Only https URLs are accepted."); return INPUT_ERROR; }
                if (!fileName.matches("[A-Za-z0-9_.-]+\\.xsd")) { System.err.println("Invalid file name."); return INPUT_ERROR; }
                HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) { System.err.println("HTTP " + r.statusCode()); return INPUT_ERROR; }
                String actual = sha256(r.body());
                java.util.Optional<String> expected;
                try {
                    expected = SchemaChecksums.expected(dir, fileName);
                } catch (IOException e) {
                    expected = java.util.Optional.empty();
                }
                if (expected.isPresent() && !expected.get().equals(actual)) {
                    System.err.println("REFUSED: " + fileName + " from " + url + " has sha256=" + actual + " but " + SchemaChecksumsFile + " lists " + expected.get()
                            + ". Either FURS published a new version (add it under its new name, update FormType and the writers) or the download is wrong.");
                    return SCHEMA_MISSING;
                }
                Files.createDirectories(dir);
                Files.write(dir.resolve(fileName), r.body());
                System.out.println("Saved " + fileName + " sha256=" + actual + " from " + url);
                if (expected.isEmpty()) {
                    System.err.println("WARNING: " + fileName + " is not listed in " + SchemaChecksumsFile + "; verify it against the official FURS page, then add the line above to it.");
                }
                return 0;
            }
        }
    }

    static final String SchemaChecksumsFile = si.opendavki.furs.SchemaChecksums.FILE_NAME;

    static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static int report(FormType form, String xml, Path schemaDir, Path label) {
        try {
            SchemaValidator.Result r = new SchemaValidator(schemaDir).validate(form, xml);
            r.issues().forEach(i -> System.err.println("  " + i));
            if (r.valid()) {
                System.err.println("VALID against " + form.xsdFileName + (label != null ? " (" + label + ")" : ""));
                return 0;
            }
            System.err.println("INVALID against " + form.xsdFileName + ": " + r.issues().size() + " issue(s)");
            return VALIDATION_FAILED;
        } catch (SchemaValidator.SchemaMissingException e) {
            System.err.println(e.getMessage());
            return SCHEMA_MISSING;
        }
    }
}
