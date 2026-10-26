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
import si.opendavki.furs.DivXmlWriter;
import si.opendavki.furs.FormType;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.model.DivReport;
import si.opendavki.model.KdvpReport;

@Command(name = "opendavki", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Open-source, local-first FURS eDavki XML tooling.",
        subcommands = {Main.Generate.class, Main.Validate.class, Main.Schemas.class})
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
                    case KDVP -> new KdvpXmlWriter().write(Json.MAPPER.readValue(input.toFile(), KdvpReport.class));
                    case DIV -> new DivXmlWriter().write(Json.MAPPER.readValue(input.toFile(), DivReport.class));
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
