package si.opendavki.tax;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import si.opendavki.furs.FormType;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.ledger.*;
import si.opendavki.model.SecurityEvent;
import si.opendavki.model.Taxpayer;

/** Reverse splits and conversions (ISIN change / share exchange). All amounts are EUR; expected values are worked out by hand. */
class ConversionAndReverseSplitTest {
    private static final Instrument A = new Instrument("US0378331005", "OLD", "Old Co", Instrument.AssetClass.STOCK);
    private static final Instrument B = new Instrument("US5949181045", "NEW", "New Co", Instrument.AssetClass.STOCK);
    private static final Instrument C = new Instrument("US0231351067", "NEWER", "Newer Co", Instrument.AssetClass.STOCK);
    private int line;

    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static LocalDate d(String s) { return LocalDate.parse(s); }
    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }
    private SourceRef ref() { return new SourceRef("test", "t.csv", "00", ++line); }

    private Trade trade(Instrument ins, Trade.Side side, String date, String qty, String price) {
        return new Trade("id" + (++line), "A", "acc", d(date), ref(), ins, side, n(qty), n(price), "EUR", null, null);
    }
    private Trade buy(Instrument ins, String date, String qty, String price) { return trade(ins, Trade.Side.BUY, date, qty, price); }
    private Trade sell(Instrument ins, String date, String qty, String price) { return trade(ins, Trade.Side.SELL, date, qty, price); }

    private CorporateAction conversion(String broker, Instrument from, Instrument to, String date, String newQty, String oldQty) {
        return new CorporateAction("c" + (++line), broker, "acc", d(date), ref(), from, CorporateAction.Type.CONVERSION, n(newQty), n(oldQty), "exchange", to);
    }
    private CorporateAction split(String date, String newQty, String oldQty) {
        return new CorporateAction("s" + (++line), "A", "acc", d(date), ref(), A, CorporateAction.Type.SPLIT, n(newQty), n(oldQty), "split");
    }

    private TaxResult run(int year, LedgerEntry... entries) {
        FxService fx = new FxService(FxConfig.defaults());
        return new TaxEngine(fx, TaxOptions.defaults()).compute(Ledger.of(List.of(entries)), year, new Taxpayer("12345678", "FO"));
    }

    private static List<SecurityEvent> events(TaxResult r) { return r.kdvp().items().get(0).security().events(); }

    private static List<String> codes(TaxResult r) { return r.diagnostics().stream().map(Diagnostic::code).toList(); }

    @Test void reverseSplitRestatesLotsIntoFewerHigherPricedShares() {
        // 100 @10; 1-for-10 reverse split -> 10 @100; sell 5 @120: gain 5 * (120 - 100) = 100
        TaxResult r = run(2024, buy(A, "2023-01-01", "100", "10"), split("2024-03-01", "1", "10"), sell(A, "2024-09-01", "5", "120"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("100", r.lotMatches().get(0).gainEur());
        assertNum("10", events(r).get(0).quantity());
        assertNum("100", events(r).get(0).unitPrice());
        assertEquals("B", events(r).get(0).acquisitionType());
        assertTrue(new KdvpXmlWriter().write(r.kdvp()).contains("<F8>5.00000000</F8>"));
    }

    @Test void conversionCarriesLotsToTheNewInstrumentKeepingDatesAndTotalCost() {
        // 10 OLD @100 (2022); 2-for-1 exchange into NEW on 2024-06-01 -> 20 @50; sell 5 NEW @60: gain 5 * 10 = 50
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2024-06-01", "2", "1"), sell(B, "2024-09-01", "5", "60"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertEquals(1, r.kdvp().items().size());
        assertEquals(B.isin(), r.kdvp().items().get(0).security().isin()); // sheet is issued for the instrument holders end up with
        assertNum("50", r.lotMatches().get(0).gainEur());
        assertEquals(d("2022-01-01"), r.lotMatches().get(0).lotDate()); // acquisition date carried over
        SecurityEvent purchase = events(r).get(0);
        assertEquals(d("2022-01-01"), purchase.date());
        assertNum("20", purchase.quantity());
        assertNum("50", purchase.unitPrice());
        assertEquals("E", purchase.acquisitionType()); // received in an exchange of capital
        assertNum("5", events(r).get(1).quantity());
        assertNum("60", events(r).get(1).unitPrice());
        assertTrue(new KdvpXmlWriter().write(r.kdvp()).contains("<F8>15.00000000</F8>")); // 20 - 5
        assertTrue(codes(r).contains("CONVERSION_ASSUMED_TAX_NEUTRAL"));
        SchemaValidator v = new SchemaValidator(Path.of("../../schemas"));
        assertTrue(v.validate(FormType.KDVP, new KdvpXmlWriter().write(r.kdvp())).valid());
    }

    @Test void conversionInAnEarlierYearIsRememberedInTheCarriedLot() {
        // 10 OLD @100 (2022), 1:1 ISIN change in 2023, sell 4 NEW @130 in 2024: gain 4 * 30 = 120
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2023-03-01", "1", "1"), sell(B, "2024-02-01", "4", "130"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("120", r.lotMatches().get(0).gainEur());
        assertEquals("E", events(r).get(0).acquisitionType());
        assertNum("10", events(r).get(0).quantity());
    }

    @Test void purchaseBeforeAConversionInTheSameYearIsMarkedAsExchanged() {
        TaxResult r = run(2024, buy(A, "2024-01-10", "10", "100"), conversion("A", A, B, "2024-06-01", "1", "1"), sell(B, "2024-09-01", "4", "130"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("120", r.lotMatches().get(0).gainEur());
        assertEquals("E", events(r).get(0).acquisitionType());
    }

    @Test void purchaseAfterTheConversionStaysAPlainPurchase() {
        // 10 OLD @100 -> 1:1 NEW on 2024-06-01; buy 5 NEW @120 on 2024-07-01; sell 12 NEW @150 on 2024-09-01
        // FIFO: 10 @100 -> 500, then 2 @120 -> 60 = 560
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2024-06-01", "1", "1"),
                buy(B, "2024-07-01", "5", "120"), sell(B, "2024-09-01", "12", "150"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("560", r.lotMatches().stream().map(LotMatch::gainEur).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals("E", events(r).get(0).acquisitionType());
        assertEquals("B", events(r).get(1).acquisitionType());
    }

    @Test void chainOfConversionsEndsAtTheLastInstrument() {
        // 10 @100 -> (2-for-1) 20 @50 in 2023 -> (1-for-2) 10 @100 in 2024; sell 5 @110: gain 5 * 10 = 50
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2023-03-01", "2", "1"),
                conversion("A", B, C, "2024-03-01", "1", "2"), sell(C, "2024-09-01", "5", "110"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertEquals(C.isin(), r.kdvp().items().get(0).security().isin());
        assertNum("50", r.lotMatches().get(0).gainEur());
        assertNum("10", events(r).get(0).quantity());
        assertNum("100", events(r).get(0).unitPrice());
    }

    @Test void theSameConversionFromTwoBrokersIsAppliedOnce() {
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2024-06-01", "2", "1"),
                conversion("X", A, B, "2024-06-01", "2", "1"), sell(B, "2024-09-01", "20", "60"));
        assertTrue(r.complete(), r.diagnostics().toString()); // applied twice there would be 40 shares and no error either...
        assertNum("20", r.lotMatches().get(0).quantity());
        assertNum("200", r.lotMatches().get(0).gainEur()); // 20 * (60 - 50)
        assertTrue(codes(r).contains("CONVERSION_DUPLICATE"));
        // ...so prove it: selling 21 must fail because only 20 shares exist
        TaxResult tooMany = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2024-06-01", "2", "1"),
                conversion("X", A, B, "2024-06-01", "2", "1"), sell(B, "2024-09-01", "21", "60"));
        assertFalse(tooMany.complete());
        assertTrue(codes(tooMany).contains("MISSING_PURCHASE_HISTORY"));
    }

    @Test void oneInstrumentConvertedIntoTwoTargetsIsAnError() {
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2023-03-01", "1", "1"),
                conversion("A", A, C, "2023-04-01", "1", "1"), sell(B, "2024-09-01", "1", "60"));
        assertTrue(codes(r).contains("CONFLICTING_CONVERSION"));
        assertFalse(r.complete());
    }

    @Test void conversionsAfterTheReportYearAreIgnored() {
        TaxResult r = run(2024, buy(A, "2022-01-01", "10", "100"), sell(A, "2024-02-01", "4", "130"), conversion("A", A, B, "2025-03-01", "1", "1"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertEquals(A.isin(), r.kdvp().items().get(0).security().isin());
        assertEquals("B", events(r).get(0).acquisitionType());
    }

    @Test void conversionNeedsATargetDifferentFromTheSource() {
        assertThrows(IllegalArgumentException.class, () -> new CorporateAction("c", "A", "acc", d("2024-01-01"), ref(), A,
                CorporateAction.Type.CONVERSION, n("1"), n("1"), "x", null));
        assertThrows(IllegalArgumentException.class, () -> new CorporateAction("c", "A", "acc", d("2024-01-01"), ref(), A,
                CorporateAction.Type.CONVERSION, n("1"), n("1"), "x", A));
        assertThrows(IllegalArgumentException.class, () -> new CorporateAction("c", "A", "acc", d("2024-01-01"), ref(), A,
                CorporateAction.Type.SPLIT, n("2"), n("1"), "x", B));
    }

    @Test void checkerMovesTheOldPositionToTheTarget() {
        List<Diagnostic> ok = LedgerChecker.check(Ledger.of(List.of(buy(A, "2022-01-01", "10", "100"),
                conversion("A", A, B, "2024-06-01", "2", "1"), sell(B, "2024-09-01", "20", "60"))));
        assertEquals(List.of(), ok.stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).toList());
        List<Diagnostic> tooMuch = LedgerChecker.check(Ledger.of(List.of(buy(A, "2022-01-01", "10", "100"),
                conversion("A", A, B, "2024-06-01", "2", "1"), sell(B, "2024-09-01", "21", "60"))));
        assertEquals("MISSING_PURCHASE_HISTORY", tooMuch.stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).findFirst().orElseThrow().code());
        List<Diagnostic> none = LedgerChecker.check(Ledger.of(List.of(conversion("A", A, B, "2024-06-01", "1", "1"))));
        assertEquals("CONVERSION_WITHOUT_POSITION", none.get(0).code());
    }

    @Test void conversionTargetWithoutIsinIsWarned() {
        Instrument noIsin = new Instrument(null, "NOISIN", "No ISIN Co", Instrument.AssetClass.STOCK);
        List<Diagnostic> d = LedgerChecker.check(Ledger.of(List.of(buy(A, "2022-01-01", "10", "100"), conversion("A", A, noIsin, "2024-06-01", "1", "1"))));
        assertTrue(d.stream().anyMatch(x -> x.code().equals("MISSING_ISIN") && x.message().contains("NOISIN")));
    }

    @Test void conversionSerialisesAndRoundTrips() throws Exception {
        Ledger l = Ledger.of(List.of(buy(A, "2022-01-01", "10", "100"), conversion("A", A, B, "2024-06-01", "2", "1")));
        Ledger back = si.opendavki.json.Json.MAPPER.readValue(si.opendavki.json.Json.MAPPER.writeValueAsString(l), Ledger.class);
        assertEquals(l, back);
        // a ledger written before conversions existed (no "target" property) still reads
        String old = si.opendavki.json.Json.MAPPER.writeValueAsString(Ledger.of(List.of(split("2024-03-01", "2", "1"))));
        assertFalse(old.contains("target"));
        assertEquals(1, si.opendavki.json.Json.MAPPER.readValue(old, Ledger.class).entries().size());
    }
}
