package si.opendavki.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FxTest {
    private static final Path ECB_FILE = Path.of("src/test/resources/fx/ecb-sample.xml");
    private static final LocalDate MAR1 = LocalDate.parse("2024-03-01"), SAT = LocalDate.parse("2024-03-02");

    private static FxConfig cfg(Map<String, String> env) { return FxConfig.from(env); }

    @Test void defaultSourceIsEcbWithDocumentedDefaults() {
        FxConfig c = FxConfig.defaults();
        assertEquals(FxSource.ECB, c.source());
        assertEquals(FxConfig.MissingDayPolicy.PREVIOUS, c.missingDay());
        assertEquals(7, c.maxLookbackDays());
        assertEquals(FxConfig.DEFAULT_ECB_URL, c.ecbUrl());
        assertNull(c.bsiUrl());
    }

    @Test void configRejectsUnknownValues() {
        assertThrows(IllegalArgumentException.class, () -> cfg(Map.of("OPENDAVKI_FX_SOURCE", "XE")));
        assertThrows(IllegalArgumentException.class, () -> cfg(Map.of("OPENDAVKI_FX_MISSING_DAY", "GUESS")));
        assertThrows(IllegalArgumentException.class, () -> cfg(Map.of("OPENDAVKI_FX_MAX_LOOKBACK_DAYS", "-1")));
    }

    @Test void dotEnvFileOverridesDefaultsAndRealEnvironmentWins(@TempDir Path dir) throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, "# comment\nexport OPENDAVKI_FX_SOURCE=file\nOPENDAVKI_FX_FILE=\"/x/rates.csv\"  \nOPENDAVKI_FX_MAX_LOOKBACK_DAYS=3 # days\n");
        var m = DotEnv.read(env);
        FxConfig c = FxConfig.from(m);
        assertEquals(FxSource.FILE, c.source());
        assertEquals(Path.of("/x/rates.csv"), c.file());
        assertEquals(3, c.maxLookbackDays());
        assertTrue(DotEnv.read(dir.resolve("missing")).isEmpty());
    }

    @Test void ecbXmlIsParsedExactlyAndConvertedToEur() throws Exception {
        FxService s = new FxService(cfg(Map.of()), uri -> Files.readAllBytes(ECB_FILE));
        Rate r = s.rate("USD", MAR1);
        assertEquals(0, new BigDecimal("1.0834").compareTo(r.unitsPerEur()));
        assertFalse(r.isFallback());
        assertEquals(new BigDecimal("92.302012183866"), r.toEur(new BigDecimal("100")));
        assertEquals(FxSource.ECB, r.source());
    }

    @Test void eurNeedsNoRate() {
        Rate r = new FxService(cfg(Map.of()), uri -> { throw new IOException("offline"); }).rate("EUR", MAR1);
        assertEquals(0, BigDecimal.ONE.compareTo(r.unitsPerEur()));
    }

    @Test void weekendUsesPreviousPublishedRateAndSaysSo() throws Exception {
        FxService s = new FxService(cfg(Map.of()), uri -> Files.readAllBytes(ECB_FILE));
        Rate r = s.rate("USD", SAT);
        assertTrue(r.isFallback());
        assertEquals(MAR1, r.rateDate());
        assertEquals(SAT, r.requestedDate());
    }

    @Test void failPolicyAndLookbackLimitRefuseToGuess() {
        var failing = new FxService(cfg(Map.of("OPENDAVKI_FX_MISSING_DAY", "FAIL")), uri -> Files.readAllBytes(ECB_FILE));
        assertThrows(RateUnavailableException.class, () -> failing.rate("USD", SAT));
        var shortLookback = new FxService(cfg(Map.of("OPENDAVKI_FX_MAX_LOOKBACK_DAYS", "0")), uri -> Files.readAllBytes(ECB_FILE));
        assertThrows(RateUnavailableException.class, () -> shortLookback.rate("USD", SAT));
        var unknownCurrency = new FxService(cfg(Map.of()), uri -> Files.readAllBytes(ECB_FILE));
        assertThrows(RateUnavailableException.class, () -> unknownCurrency.rate("JPY", MAR1));
    }

    @Test void csvFileSourceWorksOffline() {
        FxService s = new FxService(cfg(Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", "src/test/resources/fx/rates.csv")),
                uri -> { throw new IOException("must not download"); });
        assertEquals(0, new BigDecimal("1.0849").compareTo(s.rate("USD", LocalDate.parse("2024-03-04")).unitsPerEur()));
        assertEquals(FxSource.FILE, s.rate("USD", MAR1).source());
    }

    @Test void fileSourceWithoutFileAndBsiWithoutUrlExplainThemselves() {
        assertTrue(assertThrows(RateUnavailableException.class,
                () -> new FxService(cfg(Map.of("OPENDAVKI_FX_SOURCE", "FILE"))).rate("USD", MAR1)).getMessage().contains("OPENDAVKI_FX_FILE"));
        assertTrue(assertThrows(RateUnavailableException.class,
                () -> new FxService(cfg(Map.of("OPENDAVKI_FX_SOURCE", "BSI"))).rate("USD", MAR1)).getMessage().contains("OPENDAVKI_FX_BSI_URL"));
    }

    @Test void bsiSourceReadsConfiguredUrlInEcbOrCsvLayout() {
        var xml = new FxService(cfg(Map.of("OPENDAVKI_FX_SOURCE", "BSI", "OPENDAVKI_FX_BSI_URL", "https://example.invalid/x")),
                uri -> Files.readAllBytes(ECB_FILE));
        assertEquals(FxSource.BSI, xml.rate("GBP", MAR1).source());
        var csv = new FxService(cfg(Map.of("OPENDAVKI_FX_SOURCE", "BSI", "OPENDAVKI_FX_BSI_URL", "https://example.invalid/x", "OPENDAVKI_FX_CACHE_DIR", "target/fx-test-csv")),
                uri -> Files.readAllBytes(Path.of("src/test/resources/fx/rates.csv")));
        assertEquals(0, new BigDecimal("1.0834").compareTo(csv.rate("USD", MAR1).unitsPerEur()));
    }

    @Test void downloadsAreCachedAndStaleCacheSurvivesOutage(@TempDir Path dir) throws Exception {
        FxConfig c = cfg(Map.of("OPENDAVKI_FX_CACHE_DIR", dir.toString()));
        AtomicInteger downloads = new AtomicInteger();
        new FxService(c, uri -> { downloads.incrementAndGet(); return Files.readAllBytes(ECB_FILE); }).rate("USD", MAR1);
        new FxService(c, uri -> { downloads.incrementAndGet(); return Files.readAllBytes(ECB_FILE); }).rate("USD", MAR1);
        assertEquals(1, downloads.get(), "second service must use the cache");
        // date newer than the cache forces a refresh attempt; the outage falls back to the cache and then fails by lookback
        FxService offline = new FxService(c, uri -> { throw new IOException("offline"); });
        assertThrows(RateUnavailableException.class, () -> offline.rate("USD", LocalDate.parse("2024-06-01")));
        assertEquals(MAR1, offline.rate("USD", SAT).rateDate());
    }

    @Test void downloadFailureWithoutCacheIsReportedAndCorruptDataIsNeverCached(@TempDir Path dir) {
        FxConfig c = cfg(Map.of("OPENDAVKI_FX_CACHE_DIR", dir.toString()));
        assertThrows(RateUnavailableException.class, () -> new FxService(c, uri -> { throw new IOException("offline"); }).rate("USD", MAR1));
        assertThrows(RateUnavailableException.class, () -> new FxService(c, uri -> "<html>captcha</html>".getBytes()).rate("USD", MAR1));
        assertFalse(Files.exists(dir.resolve("ecb-hist.xml")));
    }

    @Test void conflictingDuplicateRatesAreRejected() {
        assertThrows(IOException.class, () -> RateTable.parseCsv("date,currency,rate\n2024-03-01,USD,1.08\n2024-03-01,USD,1.09\n"));
        assertThrows(IOException.class, () -> RateTable.parseCsv("a,b,c\n"));
        assertThrows(IOException.class, () -> RateTable.parseCsv("date,currency,rate\n2024-03-01,USD,0\n"));
    }
}
