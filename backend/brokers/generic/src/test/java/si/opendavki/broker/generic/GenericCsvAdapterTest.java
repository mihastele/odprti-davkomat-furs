package si.opendavki.broker.generic;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.opendavki.broker.BrokerAdapters;
import si.opendavki.broker.Csv;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.broker.testkit.AdapterFixtures;
import si.opendavki.ledger.*;

class GenericCsvAdapterTest {
    private final GenericCsvAdapter adapter = new GenericCsvAdapter();

    @Test void basicFixtureMatchesExpectedOutput() throws Exception {
        AdapterFixtures.assertFixture(adapter, Path.of("src/test/resources/fixtures/basic.csv"));
    }

    @Test void everyRowIsAccountedFor() throws Exception {
        ImportResult r = adapter.read(StatementSource.read(Path.of("src/test/resources/fixtures/basic.csv")));
        assertEquals(15, r.stats().rowsRead());
        assertEquals(10, r.stats().rowsMapped());
        assertEquals(1, r.stats().rowsSkipped());
        assertEquals(4, r.stats().rowsFailed());
        assertTrue(r.hasErrors());
        List<String> codes = r.diagnostics().stream().map(Diagnostic::code).toList();
        assertTrue(codes.containsAll(List.of("UNKNOWN_TYPE", "INVALID_NUMBER", "INVALID_DATE", "COLUMN_COUNT")), codes.toString());
    }

    @Test void identicalFillsInOneFileStayDistinctButReimportDeduplicates() throws Exception {
        StatementSource s = StatementSource.read(Path.of("src/test/resources/fixtures/basic.csv"));
        ImportResult r = adapter.read(s);
        var trades = r.entries().stream().filter(e -> e instanceof Trade).map(e -> (Trade) e).toList();
        assertEquals(2, trades.stream().filter(t -> t.quantity().intValue() == 5).map(Trade::id).distinct().count());

        var merged = Ledger.merge(java.util.stream.Stream.concat(r.entries().stream(), adapter.read(s).entries().stream()).toList());
        assertEquals(r.entries().size(), merged.ledger().entries().size());
        assertTrue(merged.diagnostics().stream().allMatch(d -> d.code().equals("DUPLICATE_ENTRY")));
        assertEquals(r.entries().size(), merged.diagnostics().size());
    }

    @Test void conflictingDuplicateIsAnError() {
        var a = adapter.read(StatementSource.ofText("a.csv", csv("BUY,2024-01-01,b,x,ID1,US0378331005,AAPL,,STOCK,1,10,USD")));
        var b = adapter.read(StatementSource.ofText("b.csv", csv("BUY,2024-01-01,b,x,ID1,US0378331005,AAPL,,STOCK,2,10,USD")));
        var m = Ledger.merge(java.util.stream.Stream.concat(a.entries().stream(), b.entries().stream()).toList());
        assertEquals(2, m.ledger().entries().size());
        assertEquals("CONFLICTING_DUPLICATE", m.diagnostics().get(0).code());
    }

    @Test void checkerFlagsMissingHistoryAndUnsupportedActionsAndMissingIsin() throws Exception {
        ImportResult r = adapter.read(StatementSource.read(Path.of("src/test/resources/fixtures/basic.csv")));
        List<String> codes = LedgerChecker.check(Ledger.of(r.entries())).stream().map(Diagnostic::code).toList();
        assertTrue(codes.contains("UNSUPPORTED_CORPORATE_ACTION"), codes.toString());
        assertTrue(codes.contains("MISSING_ISIN"), codes.toString());
        assertFalse(codes.contains("MISSING_PURCHASE_HISTORY"), "transfer-in covers the sale: " + codes);
    }

    @Test void sellWithoutPurchaseIsMissingHistory() {
        var r = adapter.read(StatementSource.ofText("x.csv", csv("SELL,2024-01-01,b,x,,US0378331005,AAPL,,STOCK,1,10,USD")));
        var d = LedgerChecker.check(Ledger.of(r.entries()));
        assertEquals("MISSING_PURCHASE_HISTORY", d.get(0).code());
        assertEquals("x.csv:2", d.get(0).source().toString());
    }

    @Test void splitIsAppliedToPositions() {
        var r = adapter.read(StatementSource.ofText("x.csv", csv("BUY,2024-01-01,b,x,,US0378331005,AAPL,,STOCK,1,100,USD",
                "SPLIT,2024-02-01,b,x,,US0378331005,AAPL,,STOCK,,,,,,,,,4,1",
                "SELL,2024-03-01,b,x,,US0378331005,AAPL,,STOCK,4,30,USD")));
        assertEquals(List.of(), LedgerChecker.check(Ledger.of(r.entries())));
    }

    @Test void csvQuotingAndEmbeddedNewlines() {
        var r = adapter.read(StatementSource.ofText("q.csv", csv("BUY,2024-01-01,b,x,,US0378331005,AAPL,\"Apple, \"\"Inc\"\"\nmultiline\",STOCK,1,10,USD",
                "SELL,2024-01-02,b,x,,US0378331005,AAPL,,STOCK,1,12,USD")));
        assertEquals(2, r.stats().rowsMapped());
        assertEquals("Apple, \"Inc\"\nmultiline", r.entries().get(0).instrument().name());
        assertEquals(4, r.entries().get(1).source().line()); // line numbers survive multi-line fields
    }

    @Test void rejectsFileWithoutRequiredColumns() {
        var s = StatementSource.ofText("n.csv", "foo,bar\n1,2\n");
        assertFalse(adapter.accepts(s));
        assertTrue(adapter.read(s).hasErrors());
    }

    @Test void adapterIsDiscoverableViaServiceLoader() {
        assertTrue(BrokerAdapters.byId("generic").isPresent());
    }

    private static final String HEADER = "type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency,fee,fee_currency,gross,withholding,source_country,split_new,split_old";
    private static final int WIDTH = HEADER.split(",").length;

    /** Header plus rows; short rows are padded with empty trailing cells (quoted multi-line cells are not padded-split). */
    private static String csv(String... rows) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String r : rows) {
            int cells = Csv.parse(r, ',').get(0).cells().size();
            sb.append(r).append(",".repeat(WIDTH - cells)).append('\n');
        }
        return sb.toString();
    }

    private static final String CONVERSION_HEADER = "type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency,split_new,split_old,new_isin,new_symbol,new_name\n";

    @Test void conversionRowBecomesAnExplicitConversionEntry() {
        var r = adapter.read(StatementSource.ofText("c.csv", CONVERSION_HEADER
                + "CONVERSION,2024-06-01,b,x,,US0378331005,OLD,Old Co,STOCK,,,,2,1,US5949181045,NEW,New Co\n"));
        assertEquals(1, r.stats().rowsMapped());
        CorporateAction c = (CorporateAction) r.entries().get(0);
        assertEquals(CorporateAction.Type.CONVERSION, c.type());
        assertEquals("US5949181045", c.target().isin());
        assertEquals("NEW", c.target().symbol());
        assertEquals(0, new java.math.BigDecimal("2").compareTo(c.newQuantity()));
        assertEquals(0, java.math.BigDecimal.ONE.compareTo(c.oldQuantity()));
    }

    @Test void conversionWithoutTargetOrRatioFails() {
        var r = adapter.read(StatementSource.ofText("c.csv", CONVERSION_HEADER
                + "CONVERSION,2024-06-01,b,x,,US0378331005,OLD,Old Co,STOCK,,,,2,1,,,\n"
                + "CONVERSION,2024-06-01,b,x,,US0378331005,OLD,Old Co,STOCK,,,,,,US5949181045,,\n"
                + "CONVERSION,2024-06-01,b,x,,US0378331005,OLD,Old Co,STOCK,,,,1,1,US0378331005,,\n"));
        assertEquals(List.of("MISSING_FIELD", "MISSING_FIELD", "INVALID_VALUE"), r.diagnostics().stream().map(Diagnostic::code).toList());
        assertEquals(0, r.entries().size());
    }

    /** 10 OLD @100 (2022), 2-for-1 exchange into NEW (2024-06-01), sell 5 NEW @60: lot 20 @50, gain 5 * (60 - 50) = 50. */
    @Test void aConversionEnteredByHandFlowsThroughTheTaxEngine() {
        var r = adapter.read(StatementSource.ofText("c.csv", CONVERSION_HEADER
                + "BUY,2022-01-01,b,x,,US0378331005,OLD,Old Co,STOCK,10,100,EUR,,,,,\n"
                + "CONVERSION,2024-06-01,b,x,,US0378331005,OLD,Old Co,STOCK,,,,2,1,US5949181045,NEW,New Co\n"
                + "SELL,2024-09-01,b,x,,US5949181045,NEW,New Co,STOCK,5,60,EUR,,,,,\n"));
        assertFalse(r.hasErrors(), r.diagnostics().toString());
        var fx = new si.opendavki.fx.FxService(si.opendavki.fx.FxConfig.defaults());
        var result = new si.opendavki.tax.TaxEngine(fx, si.opendavki.tax.TaxOptions.defaults())
                .compute(Ledger.of(r.entries()), 2024, new si.opendavki.model.Taxpayer("12345678", "FO"));
        assertTrue(result.complete(), result.diagnostics().toString());
        assertEquals(0, new java.math.BigDecimal("50").compareTo(result.lotMatches().get(0).gainEur()));
        assertEquals("US5949181045", result.kdvp().items().get(0).security().isin());
    }
}
