package si.opendavki.broker.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.ledger.*;
import si.opendavki.model.Taxpayer;
import si.opendavki.tax.TaxEngine;
import si.opendavki.tax.TaxOptions;
import si.opendavki.tax.TaxResult;

/**
 * Keeps the shipped DRAFT profiles loadable and consistent with their SYNTHETIC example files. This proves the profiles do what
 * their authors meant on invented data; it does not prove that any real Revolut or DEGIRO file looks like this.
 */
class DraftProfilesTest {
    private static final Path PROFILES = Path.of("../../profiles/draft"), EXAMPLES = Path.of("../../examples/drafts");

    private static ProfileAdapter load(String name) throws IOException {
        return new ProfileAdapter(ProfileLoader.load(PROFILES.resolve(name)));
    }

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    @Test void everyShippedProfileLoadsAndIsMarkedDraft() throws IOException {
        try (Stream<Path> files = Files.list(PROFILES)) {
            List<Path> json = files.filter(f -> f.toString().endsWith(".json")).toList();
            assertTrue(json.size() >= 2);
            for (Path f : json) {
                Profile p = ProfileLoader.load(f);
                assertEquals(Profile.Status.DRAFT, p.status(), f.toString());
                assertTrue(p.id().endsWith("-draft"), f.toString());
                assertNotNull(p.note());
            }
        }
    }

    @Test void revolutDraftReadsTradesAndExplainsWhatItSkips() throws Exception {
        ProfileAdapter a = load("revolut-trading-draft.json");
        StatementSource s = StatementSource.read(EXAMPLES.resolve("revolut-synthetic.csv"));
        assertTrue(a.accepts(s));
        ImportResult r = a.read(s);
        assertEquals(4, r.stats().rowsRead());
        assertEquals(2, r.stats().rowsMapped());
        List<String> codes = r.diagnostics().stream().map(Diagnostic::code).toList();
        assertEquals(List.of("DRAFT_IMPORTER", "SKIPPED_ACTION", "UNSUPPORTED_DIVIDEND"), codes);
        assertFalse(codes.contains("TOTAL_MISMATCH")); // 2 x 150.00 = 300.00 and 1 x 200.00 = 200.00
    }

    @Test void degiroDraftReadsSignedTradesWithDecimalCommas() throws Exception {
        ProfileAdapter a = load("degiro-transactions-draft.json");
        StatementSource s = StatementSource.read(EXAMPLES.resolve("degiro-synthetic.csv"));
        assertTrue(a.accepts(s));
        ImportResult r = a.read(s);
        assertEquals(2, r.stats().rowsMapped());
        Trade buy = (Trade) r.entries().get(0), sell = (Trade) r.entries().get(1);
        assertEquals(Trade.Side.BUY, buy.side());
        assertEquals(0, n("75.00").compareTo(buy.price()));
        assertEquals(0, n("1.00").compareTo(buy.fee()));
        assertEquals(Trade.Side.SELL, sell.side());
        assertEquals(0, n("4").compareTo(sell.quantity()));
        assertEquals("IE00B3XXRP09", sell.instrument().isin());
        assertEquals("2024-02-20", sell.date().toString());
    }

    /** Buy 10 @75.00 + 1.00 fee (unit 75.10); sell 4 @85.00 - 1.00 fee (unit 84.75): gain 4 x (84.75 - 75.10) = 38.60. */
    @Test void degiroExampleFlowsThroughTheTaxEngineAndIsFlaggedAsDraft() throws Exception {
        ImportResult r = load("degiro-transactions-draft.json").read(StatementSource.read(EXAMPLES.resolve("degiro-synthetic.csv")));
        FxService fx = new FxService(FxConfig.defaults());
        TaxResult result = new TaxEngine(fx, TaxOptions.defaults()).compute(Ledger.of(r.entries()), 2024, new Taxpayer("12345678", "FO"));
        assertTrue(result.complete(), result.diagnostics().toString());
        assertEquals(0, n("38.60").compareTo(result.lotMatches().get(0).gainEur()));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("DRAFT_SOURCE_USED")));
    }

    /** Buy 2 USD @150 at 1.10: unit 136.363636363636. Sell 1 @200 at 1.07: 186.915887850467. Gain 50.552251486831 (computed with Python decimals). */
    @Test void revolutExampleNeedsTheInstrumentMasterForItsMissingIsin() throws Exception {
        ImportResult r = load("revolut-trading-draft.json").read(StatementSource.read(EXAMPLES.resolve("revolut-synthetic.csv")));
        FxService fx = new FxService(FxConfig.from(Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", EXAMPLES.resolve("rates.csv").toString())));
        TaxResult without = new TaxEngine(fx, TaxOptions.defaults()).compute(Ledger.of(r.entries()), 2024, new Taxpayer("12345678", "FO"));
        assertFalse(without.complete());
        assertEquals("MISSING_ISIN", without.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).findFirst().orElseThrow().code());

        InstrumentMaster master = InstrumentMaster.fromEnvironment(Map.of("OPENDAVKI_INSTRUMENTS_FILE", EXAMPLES.resolve("instruments.csv").toString())).orElseThrow();
        TaxResult with = new TaxEngine(fx, TaxOptions.defaults()).compute(master.apply(Ledger.of(r.entries())).ledger(), 2024, new Taxpayer("12345678", "FO"));
        assertTrue(with.complete(), with.diagnostics().toString());
        assertEquals(0, n("50.552251486831").compareTo(with.lotMatches().get(0).gainEur()));
    }
}
