package si.opendavki.fx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Minimal {@code .env} reader: {@code KEY=VALUE}, {@code #} comments, optional {@code export}, optional quotes. */
public final class DotEnv {
    private DotEnv() {}

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
}
