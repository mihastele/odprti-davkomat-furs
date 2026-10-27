package si.opendavki.cli;

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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.util.ArrayList;
import java.util.List;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.BrokerAdapters;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.ledger.Ledger;
import si.opendavki.ledger.LedgerChecker;
import si.opendavki.ledger.LedgerEntry;
import si.opendavki.furs.DivXmlWriter;
import si.opendavki.furs.FormType;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.model.DivReport;
import si.opendavki.model.KdvpReport;

@Command(name = "opendavki", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Open-source, local-first FURS eDavki XML tooling.",
        subcommands = {Main.Generate.class, Main.Validate.class, Main.Schemas.class, Main.Adapters.class,
                Main.Import.class, Main.Check.class, Main.Fx.class, Main.Report.class})
public final class Main implements Callable<Integer> {

    /** Exit codes: 0 ok, 1 XSD validation failed, 2 usage/input error, 3 schema missing. */
    static final int VALIDATION_FAILED = 1, INPUT_ERROR = 2, SCHEMA_MISSING = 3;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    static final String DEFAULT_SCHEMA_DIR = "schemas";

    @Command(name = "generate", description = "Generate XML from a JSON input file and validate it against the XSD.")
    static final class Generate implements Callable<Integer> {
        @Parameters(index = "0", description = "Form: KDVP or DIV") FormType form;
        @Parameters(index = "1", description = "Input JSON file (synthetic or normalized ledger export)") Path input;
        @Option(names = {"-o", "--out"}, description = "Output XML file (default: stdout)") Path out;
        @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR, description = "Directory with official XSDs") Path schemaDir;
        @Option(names = "--skip-validation", description = "Write XML without XSD validation (NOT for filing)") boolean skip;

        @Override public Integer call() throws IOException {
            String xml;
            try {
                xml = switch (form) {
                    case KDVP -> new KdvpXmlWriter().write(si.opendavki.json.Json.MAPPER.readValue(input.toFile(), KdvpReport.class));
                    case DIV -> new DivXmlWriter().write(si.opendavki.json.Json.MAPPER.readValue(input.toFile(), DivReport.class));
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
            return report(form, xml, schemaDir, out);
        }
    }

    @Command(name = "validate", description = "Validate an existing XML file against the official XSD.")
    static final class Validate implements Callable<Integer> {
        @Parameters(index = "0", description = "Form: KDVP or DIV") FormType form;
        @Parameters(index = "1", description = "XML file") Path xmlFile;
        @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR, description = "Directory with official XSDs") Path schemaDir;

        @Override public Integer call() throws IOException {
            return report(form, Files.readString(xmlFile, StandardCharsets.UTF_8), schemaDir, xmlFile);
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

        @Override public Integer call() throws IOException {
            List<LedgerEntry> all = new ArrayList<>();
            List<Diagnostic> diags = new ArrayList<>();
            for (Path p : statements) {
                StatementSource src = StatementSource.read(p);
                BrokerAdapter adapter;
                if (adapterId != null) {
                    adapter = BrokerAdapters.byId(adapterId).orElse(null);
                    if (adapter == null) { System.err.println("Unknown adapter '" + adapterId + "'. See `adapters`."); return INPUT_ERROR; }
                } else {
                    try { adapter = BrokerAdapters.detect(src).orElse(null); }
                    catch (IllegalStateException e) { System.err.println(e.getMessage()); return INPUT_ERROR; }
                    if (adapter == null) { System.err.println("No adapter recognises " + p + "; pass --adapter."); return INPUT_ERROR; }
                }
                ImportResult r = adapter.read(src);
                System.err.printf("%s [%s]: %d rows read, %d mapped, %d skipped, %d failed%n", p, adapter.id(),
                        r.stats().rowsRead(), r.stats().rowsMapped(), r.stats().rowsSkipped(), r.stats().rowsFailed());
                all.addAll(r.entries());
                diags.addAll(r.diagnostics());
            }
            Ledger.MergeResult merged = Ledger.merge(all);
            diags.addAll(merged.diagnostics());
            diags.addAll(LedgerChecker.check(merged.ledger()));
            Files.writeString(out, si.opendavki.json.Json.MAPPER.writeValueAsString(merged.ledger()) + "\n", StandardCharsets.UTF_8);
            return printDiagnostics(diags, merged.ledger().entries().size() + " ledger entries written to " + out);
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
        @Option(names = "--out-dir", defaultValue = ".", description = "Where to write kdvp-<year>.xml, div-<year>.xml, audit-<year>.json") Path outDir;
        @Option(names = "--env-file", defaultValue = ".env", description = "Settings file (see .env.example)") Path envFile;
        @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR, description = "Directory with official XSDs") Path schemaDir;
        @Option(names = "--allow-incomplete", description = "Write XML even if some instruments/payments were left out because of errors (NOT for filing)") boolean allowIncomplete;

        @Override public Integer call() throws IOException {
            si.opendavki.model.Taxpayer taxpayer;
            si.opendavki.fx.FxConfig fxConfig;
            si.opendavki.tax.TaxOptions options;
            Ledger ledger;
            try {
                taxpayer = new si.opendavki.model.Taxpayer(taxNumber, "FO");
                java.util.Map<String, String> env = si.opendavki.fx.DotEnv.environment(envFile);
                fxConfig = si.opendavki.fx.FxConfig.from(env);
                options = si.opendavki.tax.TaxOptions.from(env);
                ledger = si.opendavki.json.Json.MAPPER.readValue(ledgerFile.toFile(), Ledger.class);
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                System.err.println("Input error: " + e.getMessage());
                return INPUT_ERROR;
            }

            si.opendavki.tax.TaxResult result = new si.opendavki.tax.TaxEngine(new si.opendavki.fx.FxService(fxConfig), options)
                    .compute(ledger, year, taxpayer);
            Files.createDirectories(outDir);
            Path audit = outDir.resolve("audit-" + year + ".json");
            Files.writeString(audit, si.opendavki.json.Json.MAPPER.writeValueAsString(result.audit()) + "\n", StandardCharsets.UTF_8);
            printGains(result);
            int exit = printDiagnostics(result.diagnostics(), "Audit trail written to " + audit + " (" + result.lotMatches().size()
                    + " lot matches, " + result.rates().size() + " rates)");
            if (!result.complete() && !allowIncomplete) {
                System.err.println("REFUSING to write XML: the result is incomplete. Fix the errors above, or use --allow-incomplete to inspect partial output.");
                return VALIDATION_FAILED;
            }
            if (!result.complete()) System.err.println("WARNING: incomplete result written because of --allow-incomplete. Do not file it.");
            if (result.kdvp() == null && result.div() == null) {
                System.err.println("Nothing to declare for " + year + ".");
                return exit;
            }
            if (result.kdvp() != null) {
                exit = Math.max(exit, writeAndValidate(FormType.KDVP, new KdvpXmlWriter().write(result.kdvp()), outDir.resolve("kdvp-" + year + ".xml")));
            }
            if (result.div() != null) {
                exit = Math.max(exit, writeAndValidate(FormType.DIV, new DivXmlWriter().write(result.div()), outDir.resolve("div-" + year + ".xml")));
            }
            return exit;
        }

        private int writeAndValidate(FormType form, String xml, Path file) throws IOException {
            Files.writeString(file, xml, StandardCharsets.UTF_8);
            return report(form, xml, schemaDir, file);
        }

        /** Per-instrument realised result in EUR, rounded to cents for reading only: eDavki computes the declared figures itself. */
        private static void printGains(si.opendavki.tax.TaxResult r) {
            java.util.Map<String, java.math.BigDecimal> gains = new java.util.TreeMap<>();
            for (si.opendavki.tax.LotMatch m : r.lotMatches()) gains.merge(m.instrument(), m.gainEur(), java.math.BigDecimal::add);
            gains.forEach((k, v) -> System.err.printf("  gain %-14s %s EUR%n", k, v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        }
    }

    @Command(name = "fx", description = "Look up the EUR conversion rate that the configured source gives for a day.")
    static final class Fx implements Callable<Integer> {
        @Parameters(index = "0", description = "ISO 4217 currency, e.g. USD") String currency;
        @Parameters(index = "1", description = "Date yyyy-MM-dd") java.time.LocalDate date;
        @Parameters(index = "2", arity = "0..1", description = "Optional amount to convert to EUR") java.math.BigDecimal amount;
        @Option(names = "--env-file", defaultValue = ".env", description = "Settings file (see .env.example)") Path envFile;

        @Override public Integer call() throws IOException {
            si.opendavki.fx.FxConfig cfg;
            try {
                cfg = si.opendavki.fx.FxConfig.fromEnvironment(envFile);
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

        @Command(name = "status", description = "Show which XSDs are present and their SHA-256.")
        static final class Status implements Callable<Integer> {
            @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR) Path schemaDir;
            @Override public Integer call() throws Exception {
                int missing = 0;
                for (FormType f : FormType.values()) {
                    Path p = schemaDir.resolve(f.xsdFileName);
                    if (Files.isRegularFile(p)) System.out.println("OK       " + f.xsdFileName + "  sha256=" + sha256(Files.readAllBytes(p)));
                    else { System.out.println("MISSING  " + f.xsdFileName); missing++; }
                }
                return missing == 0 ? 0 : SCHEMA_MISSING;
            }
        }

        @Command(name = "fetch", description = "Download an XSD from an explicit URL (copy the link from the official FURS eDavki page).")
        static final class Fetch implements Callable<Integer> {
            @Parameters(index = "0", description = "Name to save as, e.g. Doh_KDVP_9.xsd") String fileName;
            @Parameters(index = "1", description = "Source URL") URI url;
            @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR) Path schemaDir;
            @Override public Integer call() throws Exception {
                if (!"https".equals(url.getScheme())) { System.err.println("Only https URLs are accepted."); return INPUT_ERROR; }
                if (!fileName.matches("[A-Za-z0-9_.-]+\\.xsd")) { System.err.println("Invalid file name."); return INPUT_ERROR; }
                HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) { System.err.println("HTTP " + r.statusCode()); return INPUT_ERROR; }
                Files.createDirectories(schemaDir);
                Files.write(schemaDir.resolve(fileName), r.body());
                System.out.println("Saved " + fileName + " sha256=" + sha256(r.body()) + " from " + url);
                return 0;
            }
        }
    }

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
