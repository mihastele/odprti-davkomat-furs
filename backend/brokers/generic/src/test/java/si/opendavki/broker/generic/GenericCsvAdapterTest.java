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
}
