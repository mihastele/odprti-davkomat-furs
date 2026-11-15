package si.opendavki.fx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Minimal {@code .env} reader/writer: {@code KEY=VALUE}, {@code #} comments, optional {@code export}, optional quotes. */
public final class DotEnv {
    private DotEnv() {}

    /** The {@code .env} file overlaid with real {@code OPENDAVKI_*} environment variables (which win). */
    public static Map<String, String> environment(Path file) throws IOException {
        Map<String, String> merged = new HashMap<>(read(file));
        System.getenv().forEach((k, v) -> { if (k.startsWith("OPENDAVKI_")) merged.put(k, v); });
        return merged;
    }

    public static Map<String, String> read(Path file) throws IOException {
        Map<String, String> m = new HashMap<>();
        if (!Files.isRegularFile(file)) return m;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("export ")) line = line.substring(7).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException(file + ": not a KEY=VALUE line: " + raw);
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            } else {
                int hash = value.indexOf(" #");
                if (hash >= 0) value = value.substring(0, hash).trim();
            }
            m.put(key, value);
        }
        return m;
    }

    /**
     * Updates keys in an existing settings file, preserving comments and unrelated lines. The write is atomic so a
     * failed save cannot leave a half-written file that the next launch would reject.
     *
     * @throws IllegalArgumentException when a key is malformed or a value contains a line break
     */
    public static void writeUpdates(Path file, Map<String, String> updates) throws IOException {
        if (file == null) throw new IllegalArgumentException("Settings file must not be null");
        if (updates == null) throw new IllegalArgumentException("Updates must not be null");
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (e.getKey() == null || !e.getKey().matches("[A-Z][A-Z0-9_]*")) {
                throw new IllegalArgumentException("Invalid settings key: " + e.getKey());
            }
            if (e.getValue() != null && (e.getValue().contains("\n") || e.getValue().contains("\r"))) {
                throw new IllegalArgumentException("Settings value for " + e.getKey() + " must be one line");
            }
        }

        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) throw new IOException("Invalid settings path: " + absolute);
        Files.createDirectories(parent);

        ArrayList<String> lines = new ArrayList<>();
        if (Files.isRegularFile(absolute)) lines.addAll(Files.readAllLines(absolute, StandardCharsets.UTF_8));
        else lines.add("# OpenDavki settings. Saved by the local interface; comments are preserved.");

        Set<String> written = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String body = trimmed.startsWith("export ") ? trimmed.substring(7).trim() : trimmed;
            int eq = body.indexOf('=');
            if (eq <= 0) continue; // leave malformed lines untouched; the reader reports them
            String key = body.substring(0, eq).trim();
            if (!updates.containsKey(key)) continue;
            String value = updates.get(key);
            String prefix = trimmed.startsWith("export ") ? "export " : "";
            lines.set(i, prefix + key + "=" + (value == null ? "" : value));
            written.add(key);
        }
        for (Map.Entry<String, String> e : updates.entrySet()) {
            if (!written.contains(e.getKey())) lines.add(e.getKey() + "=" + (e.getValue() == null ? "" : e.getValue()));
        }

        Path temp = Files.createTempFile(parent, ".env-", ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
