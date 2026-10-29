package si.opendavki.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Medians and ratios in the comments are computed by hand. */
class LedgerCheckerTest {
    private static final Instrument AAPL = new Instrument("US0378331005", "AAPL", "Apple", Instrument.AssetClass.STOCK);
    private int line;

    private SourceRef ref() { return new SourceRef("test", "t.csv", "00", ++line); }

    private Trade buy(String date, String qty, String price, String ccy) {
        return new Trade("t" + line, "A", "acc", LocalDate.parse(date), ref(), AAPL, Trade.Side.BUY, new BigDecimal(qty), new BigDecimal(price), ccy, null, null);
    }

    private List<Diagnostic> check(String code, LedgerEntry... entries) {
        return LedgerChecker.check(Ledger.of(List.of(entries))).stream().filter(d -> d.code().equals(code)).toList();
    }

    private List<LedgerEntry> pricedBuys(String... prices) {
        List<LedgerEntry> l = new ArrayList<>();
        int day = 1;
        for (String p : prices) l.add(buy("2024-01-" + String.format("%02d", day++), "1", p, "EUR"));
        return l;
    }

    private List<Diagnostic> priceFindings(List<LedgerEntry> entries) {
        return LedgerChecker.check(Ledger.of(entries)).stream().filter(d -> d.code().equals("SUSPICIOUS_PRICE")).toList();
    }

    @Test void highOutlierIsFlagged() {
        // prices 98,100,101,102,450: median 101; 450/101 = 4.455 > 4
        List<Diagnostic> d = priceFindings(pricedBuys("100", "102", "98", "101", "450"));
        assertEquals(1, d.size());
        assertEquals("t.csv:5", d.get(0).source().toString());
        assertTrue(d.get(0).message().contains("4.455446"), d.get(0).message());
        assertEquals(Diagnostic.Severity.WARNING, d.get(0).severity());
    }

    @Test void missingSplitShowsAsLowOutlier() {
        // prices 20,98,100,102: median (98+100)/2... n=4 -> (100+98)/2 = 99; 20/99 = 0.202 < 0.25
        List<Diagnostic> d = priceFindings(pricedBuys("100", "102", "98", "20"));
        assertEquals(1, d.size());
        assertEquals("t.csv:4", d.get(0).source().toString());
    }

    @Test void recordedSplitExplainsTheLowerPrice() {
        // 100,102,98 before a 4:1 split become 25,25.5,24.5; the 26 afterwards fits: ratios all about 1
        List<LedgerEntry> l = new ArrayList<>(pricedBuys("100", "102", "98"));
        l.add(new CorporateAction("sp", "A", "acc", LocalDate.parse("2024-06-01"), ref(), AAPL, CorporateAction.Type.SPLIT,
                new BigDecimal("4"), BigDecimal.ONE, "4:1"));
        l.add(buy("2024-07-01", "1", "26", "EUR"));
        assertEquals(List.of(), priceFindings(l));
    }

    @Test void sameSplitFromTwoBrokersIsNotAppliedTwice() {
        List<LedgerEntry> l = new ArrayList<>(pricedBuys("100", "102", "98"));
        for (String broker : List.of("A", "B")) {
            l.add(new CorporateAction("sp" + broker, broker, "acc", LocalDate.parse("2024-06-01"), ref(), AAPL, CorporateAction.Type.SPLIT,
                    new BigDecimal("4"), BigDecimal.ONE, "4:1"));
        }
        l.add(buy("2024-07-01", "1", "26", "EUR"));
        assertEquals(List.of(), priceFindings(l)); // applied twice the old prices would look like 6.25 and the 26 an outlier
    }

    @Test void tooFewTradesZeroPricesAndMixedCurrenciesAreNotJudged() {
        assertEquals(List.of(), priceFindings(pricedBuys("100", "450")));
        assertEquals(List.of(), priceFindings(pricedBuys("100", "102", "98", "0"))); // a zero price (gift) is not an outlier
        List<LedgerEntry> l = new ArrayList<>(pricedBuys("100", "102", "98"));
        l.add(buy("2024-02-01", "1", "450", "USD")); // alone in USD
        assertEquals(List.of(), priceFindings(l));
    }

    @Test void quantityPrecisionBeyondEightDecimalsIsWarned() {
        assertEquals(1, check("QUANTITY_PRECISION", buy("2024-01-01", "0.123456789", "10", "EUR")).size());
        assertEquals(0, check("QUANTITY_PRECISION", buy("2024-01-01", "0.12345678", "10", "EUR")).size());
        assertEquals(0, check("QUANTITY_PRECISION", buy("2024-01-01", "1.2300000000", "10", "EUR")).size()); // trailing zeros do not count
    }

    private SecurityTransfer transfer(SecurityTransfer.Direction dir, String broker, String date, String qty) {
        return new SecurityTransfer("x" + (++line), broker, "acc", LocalDate.parse(date), ref(), AAPL, dir, new BigDecimal(qty), null, null, null, null);
    }

    @Test void pairedTransferIsQuietUnpairedOnesWarn() {
        var out = transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10");
        var in = transfer(SecurityTransfer.Direction.IN, "B", "2024-01-12", "10");
        assertEquals(0, check("TRANSFER_UNPAIRED", out, in).size());

        assertEquals(1, check("TRANSFER_UNPAIRED", transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10")).size());
        // different quantity, same account, or more than 10 days apart do not pair
        assertEquals(2, check("TRANSFER_UNPAIRED", transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10"),
                transfer(SecurityTransfer.Direction.IN, "B", "2024-01-12", "9")).size());
        assertEquals(2, check("TRANSFER_UNPAIRED", transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10"),
                transfer(SecurityTransfer.Direction.IN, "A", "2024-01-12", "10")).size());
        assertEquals(2, check("TRANSFER_UNPAIRED", transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10"),
                transfer(SecurityTransfer.Direction.IN, "B", "2024-01-21", "10")).size());
        assertEquals(0, check("TRANSFER_UNPAIRED", transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10"),
                transfer(SecurityTransfer.Direction.IN, "B", "2024-01-20", "10")).size()); // exactly 10 days
    }

    @Test void oneIncomingTransferCannotPairWithTwoOutgoingOnes() {
        var out1 = transfer(SecurityTransfer.Direction.OUT, "A", "2024-01-10", "10");
        var out2 = transfer(SecurityTransfer.Direction.OUT, "C", "2024-01-10", "10");
        var in = transfer(SecurityTransfer.Direction.IN, "B", "2024-01-11", "10");
        assertEquals(1, check("TRANSFER_UNPAIRED", out1, out2, in).size());
    }
}
