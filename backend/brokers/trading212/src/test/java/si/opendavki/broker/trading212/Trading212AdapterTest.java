package si.opendavki.broker.trading212;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.broker.testkit.AdapterFixtures;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.ledger.*;
import si.opendavki.model.Taxpayer;
import si.opendavki.tax.TaxEngine;
import si.opendavki.tax.TaxOptions;
import si.opendavki.tax.TaxResult;

/**
 * The fixture is SYNTHETIC but follows the layout and arithmetic found in a real Trading 212 export (docs/trading212.md).
 * Its totals were generated with Python from the formulas the real file obeys; values quoted below were derived there.
 */
class Trading212AdapterTest {
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/history-basic.csv");
    private final Trading212Adapter adapter = new Trading212Adapter();

    private ImportResult fixture() throws Exception { return adapter.read(StatementSource.read(FIXTURE)); }

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    private static List<String> codes(ImportResult r) { return r.diagnostics().stream().map(Diagnostic::code).toList(); }

    private <T extends LedgerEntry> T entryAtLine(ImportResult r, int line, Class<T> type) {
        return r.entries().stream().filter(e -> e.source().line() == line).map(type::cast).findFirst().orElseThrow();
    }

    @Test void fixtureMatchesExpectedOutput() throws Exception {
        AdapterFixtures.assertFixture(adapter, FIXTURE);
    }

    @Test void everyRowIsAccountedFor() throws Exception {
        var s = fixture().stats();
        assertEquals(23, s.rowsRead());
        assertEquals(14, s.rowsMapped()); // 8 trades, 4 dividends, 1 split, 1 trade that only trips the plausibility warning
        assertEquals(6, s.rowsSkipped());
        assertEquals(3, s.rowsFailed());
    }

    @Test void tradesKeepOriginalPriceCurrencyAndFee() throws Exception {
        ImportResult r = fixture();
        Trade buy = entryAtLine(r, 2, Trade.class);
        assertEquals(Trade.Side.BUY, buy.side());
        assertEquals("EOF0001", buy.id()); // the export's own order id is the ledger id
        assertEquals(java.time.LocalDate.parse("2025-03-03"), buy.date());
        assertNum("2.5", buy.quantity());
        assertNum("200", buy.price());
        assertEquals("USD", buy.currency());
        assertNum("0.71", buy.fee());
        assertEquals("EUR", buy.feeCurrency());
        assertEquals(Instrument.AssetClass.STOCK, buy.instrument().assetClass());

        Trade limit = entryAtLine(r, 3, Trade.class); // "Limit buy" is a buy; no fee column value
        assertEquals(Trade.Side.BUY, limit.side());
        assertNum("0", limit.fee());

        Trade sell = entryAtLine(r, 4, Trade.class);
        assertEquals(Trade.Side.SELL, sell.side());
        assertNum("0.30", sell.fee());
    }

    @Test void penceAreConvertedToPounds() throws Exception {
        Trade gsk = entryAtLine(fixture(), 5, Trade.class);
        assertNum("18", gsk.price()); // 1800 GBX = 18.00 GBP
        assertEquals("GBP", gsk.currency());
        assertNum("10", gsk.quantity());
    }

    @Test void sameIsinUnderDifferentTickersIsOneInstrument() throws Exception {
        ImportResult r = fixture();
        Instrument a = entryAtLine(r, 8, Trade.class).instrument(), b = entryAtLine(r, 9, Trade.class).instrument();
        assertNotEquals(a.symbol(), b.symbol());
        assertEquals(a.key(), b.key());
        assertEquals("EUR", entryAtLine(r, 8, Trade.class).currency());
        assertEquals("GBP", entryAtLine(r, 9, Trade.class).currency());
    }

    @Test void accumulatingAndDistributingFundsAreRecognisedAsEtfs() throws Exception {
        ImportResult r = fixture();
        assertEquals(Instrument.AssetClass.ETF, entryAtLine(r, 6, Trade.class).instrument().assetClass()); // (Dist)
        assertEquals(Instrument.AssetClass.ETF, entryAtLine(r, 8, Trade.class).instrument().assetClass()); // (Acc)
    }

    @Test void dividendsUseSharesTimesPerShareAndKeepWithholdingInTheDividendCurrency() throws Exception {
        ImportResult r = fixture();
        DividendPayment aapl = entryAtLine(r, 10, DividendPayment.class);
        assertNum("0.375", aapl.gross()); // 1.5 x 0.25
        assertNum("0.06", aapl.withholdingTax());
        assertEquals("USD", aapl.currency());
        assertEquals("US", aapl.sourceCountry()); // from the ISIN prefix
        DividendPayment dgse = entryAtLine(r, 12, DividendPayment.class); // withholding "-0.00" is zero, not a refund
        assertNum("0.2", dgse.gross());
        assertNum("0", dgse.withholdingTax());
        assertEquals("IE", dgse.sourceCountry());
        DividendPayment gbp = entryAtLine(r, 13, DividendPayment.class);
        assertNum("1.05", gbp.gross()); // 3 x 0.35
        assertEquals("GBP", gbp.currency());
        assertTrue(gbp.id().startsWith("t212-")); // dividend rows have no id in the export: derived from content
    }

    @Test void manufacturedPaymentIsADividendWithAWarning() throws Exception {
        ImportResult r = fixture();
        assertNum("1.5", entryAtLine(r, 11, DividendPayment.class).gross()); // 2 x 0.75
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("PAYMENT_IN_LIEU") && d.source().line() == 11));
    }

    @Test void skippedRowsExplainThemselves() throws Exception {
        ImportResult r = fixture();
        assertEquals(3, codes(r).stream().filter(c -> c.equals("CASH_MOVEMENT")).count());
        Map<String, Diagnostic.Severity> sev = new java.util.HashMap<>();
        r.diagnostics().forEach(d -> sev.put(d.code(), d.severity()));
        assertEquals(Diagnostic.Severity.WARNING, sev.get("UNSUPPORTED_DIVIDEND_TYPE")); // return of capital
        assertEquals(Diagnostic.Severity.WARNING, sev.get("UNSUPPORTED_ACTION"));        // interest on cash
        assertEquals(Diagnostic.Severity.WARNING, sev.get("UNKNOWN_ACTION"));            // "Frobnicate"
        assertEquals(Diagnostic.Severity.INFO, sev.get("CASH_MOVEMENT"));
    }

    @Test void splitRowsBecomeUnsupportedCorporateActions() throws Exception {
        CorporateAction ca = entryAtLine(fixture(), 20, CorporateAction.class);
        assertEquals(CorporateAction.Type.UNSUPPORTED, ca.type());
        assertTrue(ca.description().contains("Stock split open"));
    }

    @Test void totalPlausibilityCheckFiresOnlyForTheInconsistentRow() throws Exception {
        List<Diagnostic> mismatches = fixture().diagnostics().stream().filter(d -> d.code().equals("TOTAL_MISMATCH")).toList();
        assertEquals(1, mismatches.size());
        assertEquals(21, mismatches.get(0).source().line()); // total 105.00 where 1 x 100 / 1.0 = 100.00
        assertNum("100", entryAtLine(fixture(), 21, Trade.class).price()); // the entry itself is unchanged
    }

    @Test void badRowsFailWithSpecificCodes() throws Exception {
        List<String> errors = fixture().diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).map(Diagnostic::code).toList();
        assertEquals(List.of("INVALID_NUMBER", "INVALID_ISIN", "COLUMN_COUNT"), errors);
    }

    @Test void reimportDeduplicatesAndConflictsAreErrors() throws Exception {
        List<LedgerEntry> twice = new java.util.ArrayList<>(fixture().entries());
        twice.addAll(fixture().entries());
        var merged = Ledger.merge(twice);
        assertEquals(fixture().entries().size(), merged.ledger().entries().size());
        assertTrue(merged.diagnostics().stream().allMatch(d -> d.code().equals("DUPLICATE_ENTRY")));
    }

    @Test void negativeWithholdingAndCurrencyMismatchFail() {
        String head = "Action,Time (UTC),ISIN,Ticker,Name,Notes,ID,No. of shares,Price / share,Currency (Price / share),Exchange rate,Result,Currency (Result),Total,Currency (Total),Withholding tax,Currency (Withholding tax)\n";
        var r = adapter.read(StatementSource.ofText("d.csv", head
                + "Dividend (Dividend),2025-05-15 14:00:00+00:00,US0378331005,AAPL,Apple,,,1,0.25,USD,0.91,,,0.23,EUR,-0.05,USD\n"
                + "Dividend (Dividend),2025-05-15 14:00:00+00:00,US0378331005,AAPL,Apple,,,1,0.25,USD,0.91,,,0.23,EUR,0.05,EUR\n"));
        assertEquals(List.of("NEGATIVE_WITHHOLDING", "WITHHOLDING_CURRENCY"), codes(r));
        assertEquals(0, r.entries().size());
    }

    @Test void unknownCostColumnsAreReportedNotSilentlyIgnored() {
        String csv = "Action,Time (UTC),ISIN,Ticker,Name,Notes,ID,No. of shares,Price / share,Currency (Price / share),Exchange rate,Total,Currency (Total),Stamp duty reserve tax,Currency (Stamp duty reserve tax)\n"
                + "Market buy,2025-03-03 10:00:00+00:00,GB00BN7SWP63,GSK,GSK,,EOF1,10,1800,GBX,86,209.30,EUR,1.05,GBP\n";
        var r = adapter.read(StatementSource.ofText("s.csv", csv));
        assertEquals(1, r.entries().size());
        assertTrue(codes(r).contains("UNKNOWN_COST_COLUMN"), codes(r).toString());
    }

    @Test void detectionNeedsTheTradingLayout() throws Exception {
        assertTrue(adapter.accepts(StatementSource.read(FIXTURE)));
        assertFalse(adapter.accepts(StatementSource.ofText("x.csv", "type,date,isin,symbol,quantity,price,currency\n")));
        assertFalse(adapter.accepts(StatementSource.ofText("y.csv", "Statement,Data,BrokerName,Interactive Brokers LLC\n")));
        assertTrue(adapter.read(StatementSource.ofText("z.csv", "foo,bar\n1,2\n")).hasErrors());
    }

    /**
     * End to end with hand-derived numbers. Buy 2.5 AAPL @200 USD on 2025-03-03 (rate 1.05) with 0.71 EUR fee: unit cost
     * 200/1.05 + 0.71/2.5 = 190.476190476190 + 0.284 = 190.760190476190. Sell 1 @220 USD on 2025-06-02 (rate 1.10) with
     * 0.30 EUR fee: unit proceeds 200 - 0.30 = 199.70. Gain = 199.70 - 190.760190476190 = 8.939809523810.
     */
    @Test void importedTradesFlowThroughTheTaxEngine() throws Exception {
        List<LedgerEntry> entries = fixture().entries().stream()
                .filter(e -> e.instrument().isin() != null && e.instrument().isin().equals("US0378331005") && e instanceof Trade).toList();
        FxService fx = new FxService(FxConfig.from(Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", "src/test/resources/fixtures/rates.csv")));
        TaxResult result = new TaxEngine(fx, TaxOptions.defaults()).compute(Ledger.of(entries), 2025, new Taxpayer("12345678", "FO"));
        assertTrue(result.complete(), result.diagnostics().toString());
        assertEquals(1, result.lotMatches().size());
        assertNum("8.939809523810", result.lotMatches().get(0).gainEur());
        assertNum("190.760190476190", result.lotMatches().get(0).unitCostEur());
    }
}
