package si.opendavki.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import si.opendavki.app.AppPaths;
import si.opendavki.furs.BundledSchemas;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.fx.DotEnv;

/**
 * Resolves the settings file and schema directory from the command line, the process environment and the user directories.
 *
 * <p>The precedence is deliberately explicit: a flag wins over an environment variable, a development checkout wins over
 * the installed defaults, and the user config directory is the last fallback. Both are resolved once per command so the
 * printed paths are exactly the ones used.
 */
public final class CliPaths {
    static final String ENV_FILE = "OPENDAVKI_ENV_FILE";
    static final String SCHEMA_DIR = "OPENDAVKI_SCHEMA_DIR";

    private CliPaths() {}

    static Path cwd() {
        return Path.of("").toAbsolutePath().normalize();
    }

    static Path envFile(Path explicit) throws IOException {
        return envFile(explicit, System.getenv(), AppPaths.current(), cwd());
    }

    static Path envFile(Path explicit, Map<String, String> environment, AppPaths appPaths, Path cwd) throws IOException {
        if (explicit != null) return existing(explicit, cwd, "Settings file");
        Path fromEnv = pathFrom(environment.get(ENV_FILE), cwd);
        if (fromEnv != null) return existing(fromEnv, cwd, "Settings file from " + ENV_FILE);
        Path local = cwd.resolve(".env");
        if (Files.isRegularFile(local)) return local;
        return appPaths.envFile().toAbsolutePath().normalize();
    }

    /** Reads the settings file with {@link #envFile(Path)} precedence. Real {@code OPENDAVKI_*} variables still win. */
    public static Map<String, String> environment(Path explicit) throws IOException {
        return environmentAt(envFile(explicit));
    }

    /** Reads an already resolved settings path; a missing file means "defaults", as it does for the command line. */
    public static Map<String, String> environmentAt(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        return resolveFileSettings(DotEnv.environment(absolute), absolute, cwd());
    }

    /**
     * Turns relative file settings into absolute paths: first relative to the working directory (development checkouts and
     * the README examples), then relative to the settings file (a packaged user who puts a rates CSV next to their
     * {@code .env}). Engine file settings are only these three; cache paths are left alone.
     */
    static Map<String, String> resolveFileSettings(Map<String, String> environment, Path settingsFile, Path cwd) {
        Map<String, String> out = new LinkedHashMap<>(environment);
        for (String key : List.of("OPENDAVKI_FX_FILE", "OPENDAVKI_INSTRUMENTS_FILE", "OPENDAVKI_OVERRIDES_FILE")) {
            String value = out.get(key);
            if (value == null || value.isBlank()) continue;
            Path path = Path.of(value.trim());
            if (path.isAbsolute()) continue;
            Path inCwd = cwd.resolve(path).normalize();
            if (Files.exists(inCwd)) {
                out.put(key, inCwd.toAbsolutePath().normalize().toString());
                continue;
            }
            Path parent = settingsFile.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Path nextToSettings = parent.resolve(path).normalize();
                if (Files.exists(nextToSettings)) out.put(key, nextToSettings.toString());
            }
        }
        return out;
    }

    static Path schemaDir(Path explicit) throws IOException {
        return schemaDir(explicit, System.getenv(), AppPaths.current(), cwd());
    }

    static Path schemaDir(Path explicit, Map<String, String> environment, AppPaths appPaths, Path cwd) throws IOException {
        if (explicit != null) return absolute(explicit, cwd);
        Path fromEnv = pathFrom(environment.get(SCHEMA_DIR), cwd);
        if (fromEnv != null) return fromEnv;
        Path local = cwd.resolve("schemas");
        if (Files.isDirectory(local)) return local;
        try {
            return BundledSchemas.ensure(appPaths.schemaDir());
        } catch (IOException e) {
            throw new SchemaValidator.SchemaMissingException(e.getMessage() + " Use --schema-dir with the official XSD directory.");
        }
    }

    private static Path existing(Path path, Path cwd, String what) throws IOException {
        Path absolute = absolute(path, cwd);
        if (!Files.isRegularFile(absolute)) throw new IOException(what + " not found: " + absolute);
        return absolute;
    }

    private static Path pathFrom(String value, Path cwd) {
        if (value == null || value.isBlank()) return null;
        return absolute(Path.of(value.trim()), cwd);
    }

    private static Path absolute(Path path, Path cwd) {
        return (path.isAbsolute() ? path : cwd.resolve(path)).toAbsolutePath().normalize();
    }
}
