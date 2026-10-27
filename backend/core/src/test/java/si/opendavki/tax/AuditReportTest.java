package si.opendavki.tax;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.json.Json;
import si.opendavki.ledger.*;
import si.opendavki.model.Taxpayer;

/**
 * Same hand-computed case as TaxEngineTest.usdTradesAreConvertedAndFeesJoinCostAndProceeds:
 * buy 10 @110 USD fee 2.2 (2023-03-01, rate 1.10) -> unit cost 100.2; sell 10 @121 USD fee 1.21 (2024-03-01, rate 1.10)
 * -> unit proceeds 109.89. Cost 1002.00, proceeds 1098.90, gain 96.90.
 */
class AuditReportTest {
    private static final Instrument AAPL = new Instrument("US0378331005", "AAPL", "Apple | Inc.", Instrument.AssetClass.STOCK);

    private static Trade trade(Trade.Side side, String date, int line, String qty, String price, String fee) {
        return new Trade("t" + line, "A", "acc", LocalDate.parse(date), new SourceRef("test", "stmt.csv", "00", line), AAPL, side,
                new BigDecimal(qty), new BigDecimal(price), "USD", new BigDecimal(fee), "USD");
    }

    private TaxResult.Audit audit(boolean fees, List<LedgerEntry> entries) {
        FxService fx = new FxService(FxConfig.from(Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", "src/test/resources/fx/tax-rates.csv")));
        return new TaxEngine(fx, new TaxOptions(fees)).compute(Ledger.of(entries), 2024, new Taxpayer("12345678", "FO")).audit();
    }

    private final List<LedgerEntry> sample = List.of(trade(Trade.Side.BUY, "2023-03-01", 3, "10", "110", "2.2"),
            trade(Trade.Side.SELL, "2024-03-01", 7, "10", "121", "1.21"));

    @Test void summaryAndDetailTraceEveryFigureToSourceLines() {
        String md = AuditReport.markdown(audit(true, sample));
        assertTrue(md.contains("**Status: complete.**"), md);
        assertTrue(md.contains("| US0378331005 | AAPL | 10 | 1098.90 | 1002.00 | 96.90 |"), md);
        assertTrue(md.contains("| **Total** | | | 1098.90 | 1002.00 | 96.90 |"), md);
        assertTrue(md.contains("### 2024-03-01 sold 10 AAPL (US0378331005)"), md);
        assertTrue(md.contains("Sale line: `stmt.csv:7`"), md);
        assertTrue(md.contains("| 2023-03-01 | `stmt.csv:3` | 10 | 100.20000000 | 109.89000000 | 96.9000 |"), md);
        assertTrue(md.contains("Fees are included"), md);
    }

    @Test void ratesAreListedWithTheirDatesAndSource() {
        String md = AuditReport.markdown(audit(true, sample));
        assertTrue(md.contains("| `stmt.csv:3` | price | USD | 2023-03-01 | 2023-03-01 | 1.10 | FILE |"), md);
        assertTrue(md.contains("| `stmt.csv:7` | fee | USD | 2024-03-01 | 2024-03-01 | 1.10 | FILE |"), md);
    }

    @Test void withoutFeesTheGainIsPlainPriceDifference() {
        String md = AuditReport.markdown(audit(false, sample));
        assertTrue(md.contains("| **Total** | | | 1100.00 | 1000.00 | 100.00 |"), md); // 10*110 - 10*100
        assertTrue(md.contains("Fees are **not** included"), md);
    }

    @Test void weekendRateIsMarkedAsEarlierDay() {
        String md = AuditReport.markdown(audit(true, List.of(trade(Trade.Side.BUY, "2023-03-04", 3, "10", "110", "0"),
                trade(Trade.Side.SELL, "2024-03-01", 7, "10", "121", "0"))));
        assertTrue(md.contains("| 2023-03-04 | 2023-03-03 (earlier day) | 1.10 | FILE |"), md);
    }

    @Test void incompleteResultIsLoudAndListsTheError() {
        String md = AuditReport.markdown(audit(true, List.of(trade(Trade.Side.SELL, "2024-03-01", 7, "10", "121", "0"))));
        assertTrue(md.contains("**Status: INCOMPLETE.**"), md);
        assertTrue(md.contains("- `MISSING_PURCHASE_HISTORY` at `stmt.csv:7`"), md);
        assertTrue(md.contains("No sales are included."), md);
    }

    @Test void dividendsAppearSeparatelyFromGains() {
        DividendPayment div = new DividendPayment("dv", "A", "acc", LocalDate.parse("2024-05-15"),
                new SourceRef("test", "stmt.csv", "00", 9), AAPL, new BigDecimal("12.5"), new BigDecimal("2.5"), "USD", "US");
        String md = AuditReport.markdown(audit(true, List.of(div)));
        assertTrue(md.contains("| 2024-05-15 | Apple \\| Inc. | US | 10.00 | 2.00 | `stmt.csv:9` |"), md); // pipe in the name is escaped
        assertTrue(md.contains("| **Total** | | | 10.00 | 2.00 | |"), md);
        assertTrue(md.contains("never netted against capital gains"));
        assertTrue(md.contains("| `stmt.csv:9` | gross dividend | USD | 2024-05-15 | 2024-05-15 | 1.25 | FILE |"), md);
    }

    @Test void auditJsonRoundTripsAndRendersIdentically() throws Exception {
        TaxResult.Audit original = audit(true, sample);
        TaxResult.Audit back = Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(original), TaxResult.Audit.class);
        assertEquals(AuditReport.markdown(original), AuditReport.markdown(back));
    }

    @Test void outputIsDeterministic() {
        assertEquals(AuditReport.markdown(audit(true, sample)), AuditReport.markdown(audit(true, sample)));
    }
}
