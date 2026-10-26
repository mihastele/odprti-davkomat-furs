package si.opendavki.broker.ibkr;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.opendavki.broker.BrokerAdapters;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.broker.testkit.AdapterFixtures;
import si.opendavki.ledger.*;

/** Fixtures are SYNTHETIC, written from IBKR's documented Activity Statement layout; see docs/ibkr.md. */
class IbkrActivityAdapterTest {
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/activity-basic.csv");
    private final IbkrActivityAdapter adapter = new IbkrActivityAdapter();

    private ImportResult fixture() throws Exception { return adapter.read(StatementSource.read(FIXTURE)); }

    private static List<String> codes(ImportResult r) { return r.diagnostics().stream().map(Diagnostic::code).toList(); }

    @Test void fixtureMatchesExpectedOutput() throws Exception {
        AdapterFixtures.assertFixture(adapter, FIXTURE);
    }

    @Test void everyLineIsAccountedFor() throws Exception {
        var s = fixture().stats();
        assertEquals(s.rowsRead(), s.rowsMapped() + s.rowsSkipped() + s.rowsFailed());
    }

    @Test void tradesMapQuantitySignFeeAndThousandsSeparator() throws Exception {
        var trades = fixture().entries().stream().filter(e -> e instanceof Trade).map(e -> (Trade) e).toList();
        assertEquals(4, trades.size()); // 2 buys, 1 sell, 1 cancelled-flagged buy; execution/lot/forex/option rows excluded
        Trade etf = trades.stream().filter(t -> t.instrument().symbol().equals("VWCE")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("1000").compareTo(etf.quantity()));
        assertEquals(Instrument.AssetClass.ETF, etf.instrument().assetClass());
        assertEquals("IE00BK5BQT80", etf.instrument().isin());
        Trade sell = trades.stream().filter(t -> t.side() == Trade.Side.SELL).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("4").compareTo(sell.quantity()));
        assertEquals(0, new BigDecimal("1.1").compareTo(sell.fee()));
        assertEquals("USD", sell.currency());
        assertEquals("U1234567", sell.account());
    }

    @Test void executionLotForexAndOptionRowsAreNotSilentlyLost() throws Exception {
        var c = codes(fixture());
        assertTrue(c.containsAll(List.of("EXECUTION_DETAIL", "LOT_DETAIL", "FOREX_TRADE", "UNSUPPORTED_ASSET_CATEGORY",
                "CANCELLED_TRADE", "TOTAL_ROW", "UNSUPPORTED_SECTION", "NOT_TAX_RELEVANT")), c.toString());
        var option = fixture().diagnostics().stream().filter(d -> d.code().equals("UNSUPPORTED_ASSET_CATEGORY")).findFirst().orElseThrow();
        assertEquals(Diagnostic.Severity.WARNING, option.severity());
    }

    @Test void dividendRowsAreMergedAndWithholdingJoined() throws Exception {
        var divs = fixture().entries().stream().filter(e -> e instanceof DividendPayment).map(e -> (DividendPayment) e).toList();
        assertEquals(3, divs.size()); // AAPL (2 rows merged), VWCE, NVDA payment in lieu
        DividendPayment aapl = divs.stream().filter(d -> d.instrument().symbol().equals("AAPL")).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3.00").compareTo(aapl.gross()));
        assertEquals(0, new BigDecimal("0.45").compareTo(aapl.withholdingTax()));
        assertEquals("US", aapl.sourceCountry());
        DividendPayment vwce = divs.stream().filter(d -> d.instrument().symbol().equals("VWCE")).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(vwce.withholdingTax()));
        assertEquals("IE", vwce.sourceCountry()); // no WHT line: country inferred from the ISIN
        assertTrue(codes(fixture()).containsAll(List.of("DIVIDEND_ROWS_MERGED", "WITHHOLDING_JOINED", "PAYMENT_IN_LIEU")));
    }

    @Test void orphanWithholdingFailsLoudly() throws Exception {
        var r = fixture();
        var d = r.diagnostics().stream().filter(x -> x.code().equals("WHT_WITHOUT_DIVIDEND")).toList();
        assertEquals(1, d.size());
        assertEquals(Diagnostic.Severity.ERROR, d.get(0).severity());
        assertTrue(d.get(0).message().contains("MSFT"));
    }

    @Test void onlyForwardSplitsAreAppliedOthersAreFlagged() throws Exception {
        var cas = fixture().entries().stream().filter(e -> e instanceof CorporateAction).map(e -> (CorporateAction) e).toList();
        assertEquals(3, cas.size());
        assertEquals(1, cas.stream().filter(c -> c.type() == CorporateAction.Type.SPLIT).count());
        CorporateAction split = cas.stream().filter(c -> c.type() == CorporateAction.Type.SPLIT).findFirst().orElseThrow();
        assertEquals(0, BigDecimal.TEN.compareTo(split.newQuantity()));
        assertEquals("2024-06-07", split.date().toString());
        var warnings = LedgerChecker.check(Ledger.of(fixture().entries())).stream()
                .filter(d -> d.code().equals("UNSUPPORTED_CORPORATE_ACTION")).count();
        assertEquals(2, warnings); // reverse split + merger
    }

    @Test void transfersMapAndWarnAboutMissingCostBasis() throws Exception {
        var xfers = fixture().entries().stream().filter(e -> e instanceof SecurityTransfer).map(e -> (SecurityTransfer) e).toList();
        assertEquals(2, xfers.size());
        assertEquals("Other Broker", xfers.stream().filter(t -> t.direction() == SecurityTransfer.Direction.IN).findFirst().orElseThrow().counterparty());
        assertTrue(codes(fixture()).contains("TRANSFER_IN_NO_COST_BASIS"));
        assertTrue(codes(fixture()).contains("INVALID_DIRECTION"));
    }

    @Test void statementIsDetectedAndOthersAreNot() throws Exception {
        assertTrue(adapter.accepts(StatementSource.read(FIXTURE)));
        assertFalse(adapter.accepts(StatementSource.ofText("x.csv", "type,date,isin,symbol\n")));
        assertEquals("ibkr", BrokerAdapters.detect(StatementSource.read(FIXTURE)).orElseThrow().id());
    }

    @Test void reimportingTheSameStatementDeduplicates() throws Exception {
        var a = fixture().entries();
        var merged = Ledger.merge(java.util.stream.Stream.concat(a.stream(), fixture().entries().stream()).toList());
        assertEquals(a.size(), merged.ledger().entries().size());
        assertTrue(merged.diagnostics().stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR));
    }

    @Test void columnsAreFoundByNameNotPosition() {
        String csv = "Statement,Data,BrokerName,Interactive Brokers LLC\n"
                + "Account Information,Header,Field Name,Field Value\nAccount Information,Data,Account,U1\n"
                + "Trades,Header,Code,Symbol,Quantity,Asset Category,T. Price,Currency,Date/Time,DataDiscriminator\n"
                + "Trades,Data,O,XYZ,5,Stocks,2.5,EUR,\"2024-01-02, 10:00:00\",Order\n";
        var r = adapter.read(StatementSource.ofText("s.csv", csv));
        assertEquals(1, r.entries().size());
        assertEquals(0, new BigDecimal("2.5").compareTo(((Trade) r.entries().get(0)).price()));
        assertFalse(r.hasErrors());
    }

    @Test void dataBeforeHeaderAndWrongColumnCountFailTheRow() {
        String csv = "Statement,Data,BrokerName,Interactive Brokers LLC\n"
                + "Trades,Data,Order,Stocks,USD,AAPL\n"
                + "Dividends,Header,Currency,Date,Description,Amount\nDividends,Data,USD,2024-01-01\n";
        var r = adapter.read(StatementSource.ofText("s.csv", csv));
        assertEquals(2, r.stats().rowsFailed());
        assertEquals(List.of("COLUMN_COUNT", "COLUMN_COUNT"), r.diagnostics().stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR).map(Diagnostic::code).toList());
    }
}
