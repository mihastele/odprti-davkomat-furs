package si.opendavki.fx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Exchange-rate settings. Precedence: real environment variables, then the {@code .env} file, then defaults.
 * See {@code .env.example} for every key.
 */
public record FxConfig(FxSource source, Path cacheDir, String ecbUrl, String bsiUrl, Path file,
                       MissingDayPolicy missingDay, int maxLookbackDays) {

    public enum MissingDayPolicy {
        /** Use the latest rate published before the date (weekends, holidays), up to the lookback limit. */
        PREVIOUS,
        /** Fail if there is no rate for the exact date. */
        FAIL
    }

    public static final String DEFAULT_ECB_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-hist.xml";

    public static FxConfig defaults() {
        return from(Map.of());
    }

    public static FxConfig fromEnvironment(Path dotEnv) throws IOException {
        Map<String, String> merged = new HashMap<>(DotEnv.read(dotEnv));
        System.getenv().forEach((k, v) -> { if (k.startsWith("OPENDAVKI_")) merged.put(k, v); });
        return from(merged);
    }

    public static FxConfig from(Map<String, String> env) {
        FxSource source;
        String s = env.getOrDefault("OPENDAVKI_FX_SOURCE", "ECB").trim();
        try {
            source = FxSource.valueOf(s.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OPENDAVKI_FX_SOURCE must be ECB, BSI or FILE, got '" + s + "'");
        }
        MissingDayPolicy policy;
        String p = env.getOrDefault("OPENDAVKI_FX_MISSING_DAY", "PREVIOUS").trim();
        try {
            policy = MissingDayPolicy.valueOf(p.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("OPENDAVKI_FX_MISSING_DAY must be PREVIOUS or FAIL, got '" + p + "'");
        }
        int lookback;
        try {
            lookback = Integer.parseInt(env.getOrDefault("OPENDAVKI_FX_MAX_LOOKBACK_DAYS", "7").trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OPENDAVKI_FX_MAX_LOOKBACK_DAYS must be a whole number");
        }
        if (lookback < 0) throw new IllegalArgumentException("OPENDAVKI_FX_MAX_LOOKBACK_DAYS must be >= 0");
        String file = env.getOrDefault("OPENDAVKI_FX_FILE", "").trim();
        return new FxConfig(source,
                Path.of(env.getOrDefault("OPENDAVKI_FX_CACHE_DIR", ".opendavki/cache/fx").trim()),
                blankTo(env.get("OPENDAVKI_FX_ECB_URL"), DEFAULT_ECB_URL),
                blankTo(env.get("OPENDAVKI_FX_BSI_URL"), null),
                file.isEmpty() ? null : Path.of(file),
                policy, lookback);
    }

    private static String blankTo(String v, String d) {
        return v == null || v.isBlank() ? d : v.trim();
    }
}
