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
                Main.Import.class, Main.Check.class, Main.Fx.class, Main.Report.class, Main.Audit.class, Main.Describe.class, Main.Profiles.class})
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
        @Option(names = "--env-file", defaultValue = ".env", description = "Settings file (see .env.example)") Path envFile;
        @Option(names = "--strict", description = "Do not write the ledger if any ERROR was found") boolean strict;
        @Option(names = "--profile", description = "Profile JSON describing a CSV layout (repeatable); see docs/profiles.md") List<Path> profiles = new ArrayList<>();

        @Override public Integer call() throws IOException {
            List<LedgerEntry> all = new ArrayList<>();
            List<Diagnostic> diags = new ArrayList<>();
            List<si.opendavki.ledger.StatementCoverage.Coverage> coverage = new ArrayList<>();
            java.util.Map<String, String> env = si.opendavki.fx.DotEnv.environment(envFile);
            si.opendavki.broker.AdapterContext context;
            try {
                context = si.opendavki.broker.AdapterContext.from(env);
            } catch (IllegalArgumentException e) {
                System.err.println("Configuration error: " + e.getMessage());
                return INPUT_ERROR;
            }
            List<BrokerAdapter> extra = new ArrayList<>();
            for (Path profile : profiles) {
                try {
                    extra.add(new si.opendavki.broker.profile.ProfileAdapter(si.opendavki.broker.profile.ProfileLoader.load(profile)));
                } catch (IllegalArgumentException e) {
                    System.err.println("Profile error: " + e.getMessage());
                    return INPUT_ERROR;
                }
            }
            List<BrokerAdapter> candidates = new ArrayList<>(BrokerAdapters.all());
            candidates.addAll(extra);
            for (Path p : statements) {
                StatementSource src = StatementSource.read(p);
                BrokerAdapter adapter;
                if (adapterId != null) {
                    adapter = candidates.stream().filter(a -> a.id().equals(adapterId)).findFirst().orElse(null);
                    if (adapter == null) { System.err.println("Unknown adapter '" + adapterId + "'. See `adapters`."); return INPUT_ERROR; }
                } else {
                    List<BrokerAdapter> matching = candidates.stream().filter(a -> a.accepts(src)).toList();
                    if (matching.size() > 1) { System.err.println(p + " matches several adapters " + matching.stream().map(BrokerAdapter::id).toList() + "; choose one with --adapter"); return INPUT_ERROR; }
                    if (matching.isEmpty()) { System.err.println("No adapter recognises " + p + "; run `describe` on it, or pass --adapter / --profile."); return INPUT_ERROR; }
                    adapter = matching.get(0);
                }
                ImportResult r = adapter.read(src, context);
                si.opendavki.ledger.StatementCoverage.Coverage cov = si.opendavki.ledger.StatementCoverage.Coverage.of(p.getFileName().toString(), adapter.id(), r.entries());
                System.err.printf("%s [%s]: %d rows read, %d mapped, %d skipped, %d failed%s%n", p, adapter.id(),
                        r.stats().rowsRead(), r.stats().rowsMapped(), r.stats().rowsSkipped(), r.stats().rowsFailed(),
                        cov == null ? "" : ", entries from " + cov.first() + " to " + cov.last());
                if (cov != null) coverage.add(cov);
                all.addAll(r.entries());
                diags.addAll(r.diagnostics());
            }
            Ledger.MergeResult merged = Ledger.merge(all);
            diags.addAll(merged.diagnostics());
            Ledger ledger = merged.ledger();
            try {
                ledger = applyCorrections(ledger, env, diags);
            } catch (IllegalArgumentException e) {
                System.err.println("Correction file error: " + e.getMessage());
                return INPUT_ERROR;
            }
            diags.addAll(si.opendavki.ledger.StatementCoverage.gaps(coverage));
            diags.addAll(LedgerChecker.check(ledger));
            if (strict && diags.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR)) {
                printDiagnostics(diags, "NOT written (--strict): " + ledger.entries().size() + " ledger entries");
                return VALIDATION_FAILED;
            }
            Files.writeString(out, si.opendavki.json.Json.MAPPER.writeValueAsString(ledger) + "\n", StandardCharsets.UTF_8);
            return printDiagnostics(diags, ledger.entries().size() + " ledger entries written to " + out);
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
        @Option(names = "--out-dir", defaultValue = ".", description = "Where to write kdvp-<year>.xml, div-<year>.xml, audit-<year>.json and .md") Path outDir;
        @Option(names = "--env-file", defaultValue = ".env", description = "Settings file (see .env.example)") Path envFile;
        @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR, description = "Directory with official XSDs") Path schemaDir;
        @Option(names = "--allow-incomplete", description = "Write XML even if some instruments/payments were left out because of errors (NOT for filing)") boolean allowIncomplete;

        @Override public Integer call() throws IOException {
            si.opendavki.model.Taxpayer taxpayer;
            si.opendavki.fx.FxConfig fxConfig;
            si.opendavki.tax.TaxOptions options;
            Ledger ledger;
            List<Diagnostic> correctionDiagnostics = new ArrayList<>();
            try {
                taxpayer = new si.opendavki.model.Taxpayer(taxNumber, "FO");
                java.util.Map<String, String> env = si.opendavki.fx.DotEnv.environment(envFile);
                fxConfig = si.opendavki.fx.FxConfig.from(env);
                options = si.opendavki.tax.TaxOptions.from(env);
                ledger = si.opendavki.json.Json.MAPPER.readValue(ledgerFile.toFile(), Ledger.class);
                ledger = applyCorrections(ledger, env, correctionDiagnostics); // idempotent if `import` already applied them
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
                System.err.println("Input error: " + e.getMessage());
                return INPUT_ERROR;
            }

            si.opendavki.tax.TaxResult result = new si.opendavki.tax.TaxEngine(new si.opendavki.fx.FxService(fxConfig), options)
                    .compute(ledger, year, taxpayer, correctionDiagnostics);
            Files.createDirectories(outDir);
            Path audit = outDir.resolve("audit-" + year + ".json");
            Files.writeString(audit, si.opendavki.json.Json.MAPPER.writeValueAsString(result.audit()) + "\n", StandardCharsets.UTF_8);
            Files.writeString(outDir.resolve("audit-" + year + ".md"), si.opendavki.tax.AuditReport.markdown(result.audit()), StandardCharsets.UTF_8);
            printGains(result);
            int exit = printDiagnostics(result.diagnostics(), "Audit trail written to " + audit + " and audit-" + year + ".md (" + result.lotMatches().size()
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

    @Command(name = "describe", description = "Summarise a CSV statement's layout (column names, value shapes) without its contents; safe to share after you read it.")
    static final class Describe implements Callable<Integer> {
        @Parameters(index = "0", description = "Statement file") Path file;
        @Option(names = {"--values", "--keep"}, split = ",", description = "CSV: also list the distinct values of these columns (by header name). PDF: also show these words unmasked") List<String> valueColumns = new ArrayList<>();
        @Option(names = "--profile", description = "Check the file against this profile and say what is missing") Path profile;
        @Option(names = {"-o", "--out"}, description = "Write the description to a file instead of stdout") Path out;

        @Override public Integer call() throws IOException {
            StatementSource src = StatementSource.read(file);
            if (PdfDescriber.isPdf(src.content())) {
                if (profile != null) { System.err.println("--profile applies to CSV files only."); return INPUT_ERROR; }
                String described = PdfDescriber.describe(src.name(), src.content(), valueColumns);
                if (out != null) Files.writeString(out, described, StandardCharsets.UTF_8); else System.out.print(described);
                return 0;
            }
            StringBuilder md = new StringBuilder(si.opendavki.broker.StatementDescriber.describe(src, valueColumns));
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

    /**
     * Applies the user's correction files: ignored entries first (so a master row cannot resurrect them), then the
     * instrument master. Both are idempotent, so `report` can apply them again to a ledger written by `import`.
     */
    static Ledger applyCorrections(Ledger ledger, java.util.Map<String, String> env, List<Diagnostic> diags) throws IOException {
        java.util.Optional<si.opendavki.ledger.LedgerOverrides> overrides = si.opendavki.ledger.LedgerOverrides.fromEnvironment(env);
        if (overrides.isPresent()) {
            si.opendavki.ledger.LedgerOverrides.Result r = overrides.get().apply(ledger);
            ledger = r.ledger();
            diags.addAll(r.diagnostics());
        }
        java.util.Optional<si.opendavki.ledger.InstrumentMaster> master = si.opendavki.ledger.InstrumentMaster.fromEnvironment(env);
        if (master.isPresent()) {
            si.opendavki.ledger.InstrumentMaster.Result r = master.get().apply(ledger);
            ledger = r.ledger();
            diags.addAll(r.diagnostics());
        }
        return ledger;
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
            @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR) Path schemaDir;
            @Override public Integer call() throws Exception {
                int problems = 0;
                for (FormType f : FormType.values()) {
                    if (!Files.isRegularFile(schemaDir.resolve(f.xsdFileName))) { System.out.println("MISSING   " + f.xsdFileName); problems++; }
                }
                try {
                    for (si.opendavki.furs.SchemaChecksums.Entry e : si.opendavki.furs.SchemaChecksums.verify(schemaDir)) {
                        System.out.printf("%-9s %s  sha256=%s%s%n", e.status(), e.name(), e.actual() != null ? e.actual() : e.expected(),
                                e.status() == si.opendavki.furs.SchemaChecksums.Status.MISMATCH ? "  (expected " + e.expected() + ")" : "");
                        if (e.status() != si.opendavki.furs.SchemaChecksums.Status.OK) problems++;
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
            @Option(names = "--schema-dir", defaultValue = DEFAULT_SCHEMA_DIR) Path schemaDir;
            @Override public Integer call() throws Exception {
                if (!"https".equals(url.getScheme())) { System.err.println("Only https URLs are accepted."); return INPUT_ERROR; }
                if (!fileName.matches("[A-Za-z0-9_.-]+\\.xsd")) { System.err.println("Invalid file name."); return INPUT_ERROR; }
                HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() != 200) { System.err.println("HTTP " + r.statusCode()); return INPUT_ERROR; }
                String actual = sha256(r.body());
                java.util.Optional<String> expected;
                try {
                    expected = si.opendavki.furs.SchemaChecksums.expected(schemaDir, fileName);
                } catch (IOException e) {
                    expected = java.util.Optional.empty();
                }
                if (expected.isPresent() && !expected.get().equals(actual)) {
                    System.err.println("REFUSED: " + fileName + " from " + url + " has sha256=" + actual + " but " + SchemaChecksumsFile + " lists " + expected.get()
                            + ". Either FURS published a new version (add it under its new name, update FormType and the writers) or the download is wrong.");
                    return SCHEMA_MISSING;
                }
                Files.createDirectories(schemaDir);
                Files.write(schemaDir.resolve(fileName), r.body());
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
