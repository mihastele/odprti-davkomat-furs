package si.opendavki.fx;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/** Resolves EUR conversion rates from the configured source, caching downloads on disk. */
public final class FxService {

    /** Downloads a URL (https or file). Replaceable in tests. */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(URI uri) throws IOException;
    }

    public static final Fetcher HTTP = uri -> {
        try (InputStream in = uri.toURL().openStream()) {
            return in.readAllBytes();
        }
    };

    private static final Duration CACHE_TTL = Duration.ofHours(12);

    private final FxConfig config;
    private final Fetcher fetcher;
    private RateTable table;
    /** A failed load is remembered for the lifetime of the service so an unreachable source is tried once, not per entry. */
    private RateUnavailableException loadFailure;

    public FxService(FxConfig config) {
        this(config, HTTP);
    }

    public FxService(FxConfig config, Fetcher fetcher) {
        this.config = config;
        this.fetcher = fetcher;
    }

    public FxConfig config() { return config; }

    public Rate rate(String currency, LocalDate date) {
        if (currency.equals("EUR")) return new Rate(config.source(), "EUR", date, date, java.math.BigDecimal.ONE);
        RateTable t = table(date);
        Optional<java.math.BigDecimal> exact = t.exact(currency, date);
        if (exact.isPresent()) return new Rate(config.source(), currency, date, date, exact.get());
        if (config.missingDay() == FxConfig.MissingDayPolicy.FAIL) {
            throw new RateUnavailableException("No " + config.source() + " rate for " + currency + " on " + date
                    + " (OPENDAVKI_FX_MISSING_DAY=FAIL)");
        }
        LocalDate earliest = date.minusDays(config.maxLookbackDays());
        LocalDate found = t.latestOnOrBefore(currency, date, earliest).orElseThrow(() -> new RateUnavailableException(
                "No " + config.source() + " rate for " + currency + " on " + date + " or in the " + config.maxLookbackDays()
                        + " days before it"));
        return new Rate(config.source(), currency, date, found, t.exact(currency, found).orElseThrow());
    }

    private RateTable table(LocalDate needed) {
        if (loadFailure != null) throw loadFailure;
        if (table != null && (table.lastDate().isPresent() && !needed.isAfter(table.lastDate().get()) || config.source() == FxSource.FILE)) {
            return table;
        }
        try {
            table = switch (config.source()) {
                case FILE -> {
                    if (config.file() == null) throw new RateUnavailableException("OPENDAVKI_FX_SOURCE=FILE needs OPENDAVKI_FX_FILE");
                    yield RateTable.parse(Files.readAllBytes(config.file()));
                }
                case ECB -> cached("ecb-hist.xml", URI.create(config.ecbUrl()), needed);
                case BSI -> {
                    if (config.bsiUrl() == null) {
                        throw new RateUnavailableException("OPENDAVKI_FX_SOURCE=BSI needs OPENDAVKI_FX_BSI_URL (no default URL is "
                                + "known; use ECB, which publishes the same reference rates, or FILE)");
                    }
                    yield cached("bsi-rates.dat", URI.create(config.bsiUrl()), needed);
                }
            };
            return table;
        } catch (IOException e) {
            loadFailure = new RateUnavailableException("Cannot load " + config.source() + " rates: " + e.getMessage(), e);
            throw loadFailure;
        }
    }

    /** Uses the cached copy while it is fresh or already covers the date; otherwise downloads, falling back to a stale cache. */
    private RateTable cached(String name, URI uri, LocalDate needed) throws IOException {
        Path cache = config.cacheDir().resolve(name);
        RateTable cachedTable = null;
        if (Files.isRegularFile(cache)) {
            try {
                cachedTable = RateTable.parse(Files.readAllBytes(cache));
            } catch (IOException ignored) {
                // corrupt cache: re-download below
            }
        }
        if (cachedTable != null) {
            boolean fresh = Duration.between(Files.getLastModifiedTime(cache).toInstant(), Instant.now()).compareTo(CACHE_TTL) < 0;
            boolean covers = cachedTable.lastDate().isPresent() && !needed.isAfter(cachedTable.lastDate().get());
            if (fresh || covers) return cachedTable;
        }
        try {
            byte[] data = fetcher.fetch(uri);
            RateTable downloaded = RateTable.parse(data); // never cache something we cannot parse
            Files.createDirectories(config.cacheDir());
            Files.write(cache, data);
            return downloaded;
        } catch (IOException e) {
            if (cachedTable != null) return cachedTable;
            throw e;
        }
    }
}
