package si.opendavki.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.opendavki.app.AppPaths;
import si.opendavki.furs.SchemaChecksums;

class CliPathsTest {
    @TempDir Path tmp;

    private AppPaths app() {
        return AppPaths.forEnvironment(Map.of(), "Linux", tmp.resolve("home"));
    }

    @Test void explicitSettingsFileMustExist() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        assertThrows(IOException.class, () -> CliPaths.envFile(cwd.resolve("missing.env"), Map.of(), app(), cwd));
        Path env = Files.writeString(cwd.resolve("chosen.env"), "OPENDAVKI_FX_SOURCE=ECB\n");
        assertEquals(env.toAbsolutePath().normalize(), CliPaths.envFile(env, Map.of(), app(), cwd));
    }

    @Test void theEnvironmentVariableBeatsTheCurrentDirectory() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        Path local = Files.writeString(cwd.resolve(".env"), "A=1\n");
        Path env = Files.writeString(tmp.resolve("env-file"), "B=2\n");
        assertEquals(env.toAbsolutePath().normalize(),
                CliPaths.envFile(null, Map.of(CliPaths.ENV_FILE, env.toString()), app(), cwd));
        assertEquals(local, CliPaths.envFile(null, Map.of(), app(), cwd));
    }

    @Test void fallsBackToTheUserConfigFileAndCreatesNoFile() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        AppPaths app = app();
        assertEquals(app.envFile().toAbsolutePath().normalize(), CliPaths.envFile(null, Map.of(), app, cwd));
        assertFalse(Files.exists(app.envFile()));
    }

    @Test void aDevelopmentSchemaDirectoryWinsOverTheBundledCopy() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        Path schemas = Files.createDirectories(cwd.resolve("schemas"));
        assertEquals(schemas, CliPaths.schemaDir(null, Map.of(), app(), cwd));
    }

    @Test void theSchemaEnvironmentVariableWinsOverTheDevelopmentDirectory() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        Files.createDirectories(cwd.resolve("schemas"));
        Path custom = Files.createDirectories(tmp.resolve("custom-schemas"));
        assertEquals(custom.toAbsolutePath().normalize(),
                CliPaths.schemaDir(null, Map.of(CliPaths.SCHEMA_DIR, custom.toString()), app(), cwd));
    }

    @Test void fallsBackToTheBundledSchemasAndVerifiesThem() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        Path extracted = CliPaths.schemaDir(null, Map.of(), app(), cwd);
        assertTrue(Files.isRegularFile(extracted.resolve("Doh_KDVP_9.xsd")));
        assertTrue(SchemaChecksums.verify(extracted).stream().allMatch(e -> e.status() == SchemaChecksums.Status.OK));
    }

    @Test void aResolvedSettingsPathThatDoesNotExistMeansDefaults() throws IOException {
        // The web server is given the path that would be used for saving; a fresh install does not have the file yet.
        assertNotNull(CliPaths.environmentAt(tmp.resolve("missing/.env")));
    }

    @Test void relativeDataFilesAreFoundNextToTheSettingsFileOrInTheWorkingDirectory() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        Path settings = Files.createDirectories(tmp.resolve("config"));
        Path rates = Files.writeString(settings.resolve("rates.csv"), "date,currency,rate\n");
        Path local = Files.writeString(cwd.resolve("local.csv"), "date,currency,rate\n");
        Map<String, String> resolved = CliPaths.resolveFileSettings(
                Map.of("OPENDAVKI_FX_FILE", "rates.csv", "OPENDAVKI_INSTRUMENTS_FILE", "local.csv"), settings.resolve(".env"), cwd);
        assertEquals(rates.toAbsolutePath().normalize().toString(), resolved.get("OPENDAVKI_FX_FILE"));
        assertEquals(local.toAbsolutePath().normalize().toString(), resolved.get("OPENDAVKI_INSTRUMENTS_FILE"));
    }

    @Test void anExplicitSchemaDirectoryIsReturnedEvenIfItIsMissingSoTheValidatorCanExplainIt() throws IOException {
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        Path missing = cwd.resolve("missing-schemas");
        assertEquals(missing.toAbsolutePath().normalize(), CliPaths.schemaDir(missing, Map.of(), app(), cwd));
    }
}
