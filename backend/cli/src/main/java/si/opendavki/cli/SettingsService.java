package si.opendavki.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import si.opendavki.broker.profile.ProfileLoader;
import si.opendavki.fx.DotEnv;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxSource;
import si.opendavki.tax.TaxOptions;

/**
 * The editable part of the local settings file. The interface may write only the keys listed here, and only when the
 * user presses Save; it cannot change the process-level location variables or add arbitrary keys.
 */
public final class SettingsService {
    /** Keys the settings screen is allowed to change. */
    public static final List<String> EDITABLE_KEYS = List.of(
            "OPENDAVKI_FX_SOURCE", "OPENDAVKI_FX_MISSING_DAY", "OPENDAVKI_FX_MAX_LOOKBACK_DAYS",
            "OPENDAVKI_FX_ECB_URL", "OPENDAVKI_FX_BSI_URL", "OPENDAVKI_FX_FILE", "OPENDAVKI_FX_CACHE_DIR",
            "OPENDAVKI_INSTRUMENTS_FILE", "OPENDAVKI_OVERRIDES_FILE", "OPENDAVKI_TIMEZONE",
            "OPENDAVKI_INTEREST_TYPE_CODE", "OPENDAVKI_INCLUDE_FEES");
    private static final Set<String> FILE_KINDS = Set.of("instruments", "overrides", "fx", "profile");

    private final Path file;

    public SettingsService(Path file) {
        if (file == null) throw new IllegalArgumentException("Settings file must not be null");
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() { return file; }

    /** Values, path and a list of keys currently overridden by real environment variables (those will not change). */
    public Map<String, Object> view() throws IOException {
        Map<String, String> effective = CliPaths.environmentAt(file);
        Map<String, String> values = new LinkedHashMap<>();
        List<String> overridden = new ArrayList<>();
        for (String key : EDITABLE_KEYS) {
            values.put(key, effective.getOrDefault(key, defaultFor(key)));
            if (System.getenv().containsKey(key)) overridden.add(key);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", file.toString());
        out.put("exists", Files.isRegularFile(file));
        Path parent = file.getParent();
        out.put("writable", Files.exists(file) ? Files.isWritable(file)
                : (parent == null || !Files.exists(parent) || Files.isWritable(parent)));
        out.put("keys", EDITABLE_KEYS);
        out.put("values", values);
        out.put("overridden", overridden);
        out.put("files", listFiles());
        return out;
    }

    /** Validates the complete resulting settings, then updates only the given keys in the file. */
    public Map<String, Object> save(Map<String, String> updates) throws IOException {
        if (updates == null || updates.isEmpty()) throw new IllegalArgumentException("No settings were supplied");
        for (String key : updates.keySet()) {
            if (!EDITABLE_KEYS.contains(key)) throw new IllegalArgumentException("Unknown setting: " + key);
        }
        Map<String, String> clean = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : updates.entrySet()) clean.put(e.getKey(), e.getValue() == null ? "" : e.getValue().trim());
        Map<String, String> merged = new HashMap<>(DotEnv.read(file));
        merged.putAll(clean);
        validate(merged);
        DotEnv.writeUpdates(file, clean);
        return view();
    }

    /** Writes an uploaded data file under the settings directory and returns its absolute path. */
    public Map<String, Object> saveFile(String kind, String name, byte[] bytes) throws IOException {
        if (kind == null || !FILE_KINDS.contains(kind)) throw new IllegalArgumentException("Unknown file kind: " + kind);
        String safe = safeName(name);
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException(name + " is empty");
        if (bytes.length > 32 * 1024 * 1024) throw new IllegalArgumentException(name + " is larger than 32 MB");
        if ("profile".equals(kind)) {
            ProfileLoader.parse(new String(bytes, StandardCharsets.UTF_8), safe); // validate before writing
        }
        Path dir = file.getParent().resolve("files").resolve(kind);
        Files.createDirectories(dir);
        Path target = dir.resolve(safe).normalize();
        if (!target.startsWith(dir.normalize())) throw new IllegalArgumentException("Invalid file name: " + name);
        Path temp = Files.createTempFile(dir, ".upload-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("name", safe);
        out.put("path", target.toString());
        return out;
    }

    private Map<String, List<Map<String, String>>> listFiles() throws IOException {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        Path root = file.getParent().resolve("files");
        for (String kind : List.of("instruments", "overrides", "fx", "profile")) {
            List<Map<String, String>> files = new ArrayList<>();
            Path dir = root.resolve(kind);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(Files::isRegularFile).sorted().forEach(p -> files.add(Map.of(
                            "name", p.getFileName().toString(), "path", p.toString())));
                }
            }
            out.put(kind, files);
        }
        return out;
    }

    private static String defaultFor(String key) {
        return switch (key) {
            case "OPENDAVKI_FX_SOURCE" -> "ECB";
            case "OPENDAVKI_FX_MISSING_DAY" -> "PREVIOUS";
            case "OPENDAVKI_FX_MAX_LOOKBACK_DAYS" -> "7";
            case "OPENDAVKI_FX_ECB_URL" -> FxConfig.DEFAULT_ECB_URL;
            case "OPENDAVKI_TIMEZONE" -> "UTC";
            case "OPENDAVKI_INCLUDE_FEES" -> "true";
            default -> "";
        };
    }

    private static void validate(Map<String, String> env) {
        FxConfig fx = FxConfig.from(env);
        TaxOptions.from(env);
        String tz = env.getOrDefault("OPENDAVKI_TIMEZONE", "UTC").trim();
        if (tz.isEmpty()) tz = "UTC";
        try {
            ZoneId.of(tz);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("OPENDAVKI_TIMEZONE is not a known zone: " + tz);
        }
        if (fx.source() == FxSource.FILE && (env.getOrDefault("OPENDAVKI_FX_FILE", "").isBlank())) {
            throw new IllegalArgumentException("OPENDAVKI_FX_SOURCE=FILE needs OPENDAVKI_FX_FILE");
        }
        if (fx.source() == FxSource.BSI && (env.getOrDefault("OPENDAVKI_FX_BSI_URL", "").isBlank())) {
            throw new IllegalArgumentException("OPENDAVKI_FX_SOURCE=BSI needs OPENDAVKI_FX_BSI_URL");
        }
        for (String key : List.of("OPENDAVKI_FX_FILE", "OPENDAVKI_INSTRUMENTS_FILE", "OPENDAVKI_OVERRIDES_FILE")) {
            String value = env.getOrDefault(key, "").trim();
            if (value.isEmpty()) continue;
            Path p = Path.of(value);
            if (p.isAbsolute() && !Files.isRegularFile(p)) {
                throw new IllegalArgumentException(key + " points to a missing file: " + p);
            }
        }
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("A file name is required");
        // A path separator is never part of a file name; reject it instead of silently taking the last component.
        if (name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("Invalid file name: " + name);
        String base;
        try {
            base = Path.of(name).getFileName().toString();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid file name: " + name);
        }
        if (!base.matches("[A-Za-z0-9._-]{1,120}") || base.equals(".") || base.equals("..")) {
            throw new IllegalArgumentException("Invalid file name: " + name);
        }
        return base;
    }
}
