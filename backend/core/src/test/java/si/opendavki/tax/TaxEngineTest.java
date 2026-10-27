package si.opendavki.tax;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import si.opendavki.furs.DivXmlWriter;
import si.opendavki.furs.FormType;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.fx.FxSource;
import si.opendavki.ledger.*;
import si.opendavki.model.*;

/**
 * Expected numbers are worked out by hand in the comments (not copied from program output). The USD rates in
 * tax-rates.csv are chosen so conversions divide exactly: 110/1.10 = 100, 121/1.10 = 110, 2.2/1.10 = 2, 1.21/1.10 = 1.1.
 */
class TaxEngineTest {
    private static final Taxpayer TP = new Taxpayer("12345678", "FO");
    private static final Instrument AAPL = new Instrument("US0378331005", "AAPL", "Apple Inc.", Instrument.AssetClass.STOCK);
    private static final String USD_RATES = "src/test/resources/fx/tax-rates.csv";
    private int seq;

    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static LocalDate d(String s) { return LocalDate.parse(s); }
    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    private SourceRef ref() { return new SourceRef("test", "t.csv", "00", ++seq); }

    private Trade trade(String broker, Trade.Side side, String date, String qty, String price, String ccy, String fee, Instrument ins) {
        return new Trade("id" + seq, broker, "acc", d(date), ref(), ins, side, n(qty), n(price), ccy, n(fee), ccy);
    }
    private Trade buy(String date, String qty, String price) { return trade("A", Trade.Side.BUY, date, qty, price, "EUR", "0", AAPL); }
    private Trade sell(String date, String qty, String price) { return trade("A", Trade.Side.SELL, date, qty, price, "EUR", "0", AAPL); }

    private TaxResult run(TaxOptions opts, int year, LedgerEntry... entries) {
        FxService fx = new FxService(FxConfig.from(Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", USD_RATES)));
        return new TaxEngine(fx, opts).compute(Ledger.of(List.of(entries)), year, TP);
    }
    private TaxResult run(int year, LedgerEntry... entries) { return run(TaxOptions.defaults(), year, entries); }

    private static List<String> errorCodes(TaxResult r) {
        return r.diagnostics().stream().filter(x -> x.severity() == Diagnostic.Severity.ERROR).map(Diagnostic::code).toList();
    }
    private static List<SecurityEvent> events(TaxResult r, int item) { return r.kdvp().items().get(item).security().events(); }

    private void assertXmlValid(TaxResult r) {
        SchemaValidator v = new SchemaValidator(Path.of("../../schemas"));
        if (r.kdvp() != null) {
            var res = v.validate(FormType.KDVP, new KdvpXmlWriter().write(r.kdvp()));
            assertTrue(res.valid(), res.issues().toString());
        }
        if (r.div() != null) {
            var res = v.validate(FormType.DIV, new DivXmlWriter().write(r.div()));
            assertTrue(res.valid(), res.issues().toString());
        }
    }

    @Test void fifoMatchesOldestLotsFirst() {
        // 10 @100 and 10 @120, sell 15 @150: 10*(150-100) + 5*(150-120) = 500 + 150
        TaxResult r = run(2024, buy("2023-01-10", "10", "100"), buy("2023-06-01", "10", "120"), sell("2024-03-01", "15", "150"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertEquals(2, r.lotMatches().size());
        assertNum("10", r.lotMatches().get(0).quantity());
        assertNum("500", r.lotMatches().get(0).gainEur());
        assertNum("5", r.lotMatches().get(1).quantity());
        assertNum("150", r.lotMatches().get(1).gainEur());
        assertNum("650", r.lotMatches().stream().map(LotMatch::gainEur).reduce(BigDecimal.ZERO, BigDecimal::add));
        List<SecurityEvent> ev = events(r, 0); // two carried lots (full quantity, none sold before 2024) + the sale
        assertEquals(3, ev.size());
        assertEquals(SecurityEvent.Kind.SALE, ev.get(2).kind());
        assertNum("15", ev.get(2).quantity());
        assertXmlValid(r);
        assertTrue(new KdvpXmlWriter().write(r.kdvp()).contains("<F8>5.00000000</F8>")); // 20 held - 15 sold
    }

    @Test void priorYearSaleReducesTheCarriedLot() {
        // buy 10 @100 (2022), sell 4 @120 (2023, not in report year), sell 3 @130 (2024): 6 open on 1 Jan 2024
        TaxResult r = run(2024, buy("2022-05-01", "10", "100"), sell("2023-05-01", "4", "120"), sell("2024-02-01", "3", "130"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertEquals(1, r.lotMatches().size());
        assertNum("90", r.lotMatches().get(0).gainEur()); // 3 * (130 - 100)
        List<SecurityEvent> ev = events(r, 0);
        assertNum("6", ev.get(0).quantity());
        assertNum("100", ev.get(0).unitPrice());
        assertEquals(d("2022-05-01"), ev.get(0).date());
        assertNum("3", ev.get(1).quantity());
        assertXmlValid(r);
    }

    @Test void usdTradesAreConvertedAndFeesJoinCostAndProceeds() {
        // buy 10 @110 USD fee 2.2 USD on 2023-03-01 (rate 1.10): unit 100 EUR + fee 2/10 = 100.2
        // sell 10 @121 USD fee 1.21 USD on 2024-03-01 (rate 1.10): unit 110 - 1.1/10 = 109.89
        Trade b = trade("A", Trade.Side.BUY, "2023-03-01", "10", "110", "USD", "2.2", AAPL);
        Trade s = trade("A", Trade.Side.SELL, "2024-03-01", "10", "121", "USD", "1.21", AAPL);
        TaxResult withFees = run(2024, b, s);
        assertNum("96.9", withFees.lotMatches().get(0).gainEur()); // 10 * (109.89 - 100.2)
        assertNum("100.2", events(withFees, 0).get(0).unitPrice());
        assertNum("109.89", events(withFees, 0).get(1).unitPrice());
        assertEquals(4, withFees.rates().size()); // price and fee for the buy, price and fee for the sale
        TaxResult noFees = run(new TaxOptions(false), 2024, b, s);
        assertNum("100", noFees.lotMatches().get(0).gainEur()); // 10 * (110 - 100)
        assertEquals(2, noFees.rates().size());
        assertEquals(FxSource.FILE, noFees.rates().get(0).rateSource());
        assertXmlValid(withFees);
    }

    @Test void weekendTradeUsesPreviousRateAndRecordsIt() {
        // 2023-03-04 is a Saturday: rate of Friday 2023-03-03 (1.10) applies
        Trade b = trade("A", Trade.Side.BUY, "2023-03-04", "10", "110", "USD", "0", AAPL);
        TaxResult r = run(2024, b, trade("A", Trade.Side.SELL, "2024-03-01", "10", "121", "USD", "0", AAPL));
        RateUse use = r.rates().stream().filter(x -> x.requestedDate().equals(d("2023-03-04"))).findFirst().orElseThrow();
        assertEquals(d("2023-03-03"), use.rateDate());
        assertTrue(r.diagnostics().stream().anyMatch(x -> x.code().equals("RATE_FALLBACK")));
        assertNum("100", events(r, 0).get(0).unitPrice());
    }

    @Test void splitRestatesLotsIntoPostSplitUnits() {
        // buy 10 @100, 4-for-1 split on 2024-06-01, sell 20 @30 on 2024-09-01: lot is 40 @25, gain 20*(30-25) = 100
        CorporateAction split = new CorporateAction("sp", "A", "acc", d("2024-06-01"), ref(), AAPL,
                CorporateAction.Type.SPLIT, n("4"), n("1"), "4:1");
        TaxResult r = run(2024, buy("2023-01-01", "10", "100"), split, sell("2024-09-01", "20", "30"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("100", r.lotMatches().get(0).gainEur());
        List<SecurityEvent> ev = events(r, 0);
        assertNum("40", ev.get(0).quantity());
        assertNum("25", ev.get(0).unitPrice());
        assertNum("20", ev.get(1).quantity());
        assertNum("30", ev.get(1).unitPrice());
        assertXmlValid(r);
    }

    @Test void saleBeforeSplitIsRestatedToo() {
        // buy 10 @100; sell 2 @120 (before the split); 2:1 split; sell 3 @70 after. Final units: lot 20 @50; first sale 4 @60.
        CorporateAction split = new CorporateAction("sp", "A", "acc", d("2024-06-01"), ref(), AAPL,
                CorporateAction.Type.SPLIT, n("2"), n("1"), "2:1");
        TaxResult r = run(2024, buy("2023-01-01", "10", "100"), sell("2024-03-01", "2", "120"), split, sell("2024-09-01", "3", "70"));
        assertTrue(r.complete(), r.diagnostics().toString());
        List<SecurityEvent> ev = events(r, 0);
        assertNum("4", ev.get(1).quantity());
        assertNum("60", ev.get(1).unitPrice());
        assertNum("3", ev.get(2).quantity());
        assertNum("40", r.lotMatches().get(0).gainEur()); // 2 * (120 - 100), in units valid on the sale date
        assertNum("60", r.lotMatches().get(1).gainEur()); // 3 * (70 - 50)
        assertXmlValid(r);
    }

    @Test void sameSplitFromTwoBrokersIsAppliedOnce() {
        CorporateAction a = new CorporateAction("spA", "A", "acc", d("2024-06-01"), ref(), AAPL, CorporateAction.Type.SPLIT, n("4"), n("1"), "x");
        CorporateAction b = new CorporateAction("spB", "B", "acc", d("2024-06-01"), ref(), AAPL, CorporateAction.Type.SPLIT, n("4"), n("1"), "x");
        TaxResult r = run(2024, buy("2023-01-01", "10", "100"), a, b, sell("2024-09-01", "40", "30"));
        assertTrue(r.complete(), r.diagnostics().toString()); // applied twice would leave 160 shares and an error-free sale
        assertEquals(1, r.lotMatches().size());
        assertNum("40", r.lotMatches().get(0).quantity());
        assertNum("200", r.lotMatches().get(0).gainEur()); // 40 * (30 - 25)
        assertTrue(r.diagnostics().stream().anyMatch(x -> x.code().equals("SPLIT_DUPLICATE")));
    }

    @Test void saleWithoutPurchaseHistoryExcludesTheInstrument() {
        TaxResult r = run(2024, sell("2024-03-01", "5", "100"));
        assertFalse(r.complete());
        assertNull(r.kdvp());
        assertEquals(List.of("MISSING_PURCHASE_HISTORY", "INSTRUMENT_EXCLUDED"), errorCodes(r));
        assertTrue(r.lotMatches().isEmpty());
    }

    @Test void errorOnOneInstrumentDoesNotHideAnother() {
        Instrument other = new Instrument("US5949181045", "MSFT", "Microsoft", Instrument.AssetClass.STOCK);
        TaxResult r = run(2024, sell("2024-03-01", "5", "100"),
                trade("A", Trade.Side.BUY, "2023-01-01", "2", "10", "EUR", "0", other),
                trade("A", Trade.Side.SELL, "2024-02-01", "2", "15", "EUR", "0", other));
        assertFalse(r.complete());
        assertEquals(1, r.kdvp().items().size());
        assertEquals("US5949181045", r.kdvp().items().get(0).security().isin());
        assertNum("10", r.lotMatches().get(0).gainEur()); // 2 * (15 - 10)
    }

    @Test void transferBetweenOwnBrokersKeepsOriginalLots() {
        // bought at A in 2023 @100, moved to B, sold at B: gain uses the 2023 lot
        SecurityTransfer out = new SecurityTransfer("o", "A", "acc", d("2024-01-10"), ref(), AAPL, SecurityTransfer.Direction.OUT, n("10"), null, null, null, "B");
        SecurityTransfer in = new SecurityTransfer("i", "B", "acc", d("2024-01-12"), ref(), AAPL, SecurityTransfer.Direction.IN, n("10"), null, null, null, "A");
        TaxResult r = run(2024, buy("2023-02-01", "10", "100"), out, in, trade("B", Trade.Side.SELL, "2024-06-03", "4", "120", "EUR", "0", AAPL));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("80", r.lotMatches().get(0).gainEur()); // 4 * (120 - 100)
        assertEquals(d("2023-02-01"), r.lotMatches().get(0).lotDate());
        assertEquals(2, r.diagnostics().stream().filter(x -> x.code().equals("TRANSFER_PAIRED")).count());
        assertTrue(r.diagnostics().stream().noneMatch(x -> x.code().equals("TRANSFER_BASIS_MISSING")));
    }

    @Test void unpairedIncomingTransferWithoutBasisFailsOnSale() {
        SecurityTransfer in = new SecurityTransfer("i", "B", "acc", d("2024-01-12"), ref(), AAPL, SecurityTransfer.Direction.IN, n("10"), null, null, null, "X");
        TaxResult r = run(2024, in, sell("2024-06-03", "4", "120"));
        assertFalse(r.complete());
        assertEquals("SALE_USES_UNKNOWN_COST", errorCodes(r).get(0));
        assertTrue(r.diagnostics().stream().anyMatch(x -> x.code().equals("TRANSFER_BASIS_MISSING")));
    }

    @Test void unpairedIncomingTransferWithBasisBecomesALot() {
        // arrived with acquisition date 2022-01-01 and cost 90 EUR/unit; sell 4 @120 -> 4 * 30 = 120
        SecurityTransfer in = new SecurityTransfer("i", "B", "acc", d("2024-01-12"), ref(), AAPL, SecurityTransfer.Direction.IN, n("10"),
                d("2022-01-01"), n("90"), "EUR", "X");
        TaxResult r = run(2024, in, sell("2024-06-03", "4", "120"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("120", r.lotMatches().get(0).gainEur());
        assertEquals(d("2022-01-01"), r.lotMatches().get(0).lotDate());
        assertXmlValid(r);
    }

    @Test void sheetOfAnInYearTransferKeepsOriginalDateAndBalance() {
        // 10 arrive in 2024 with cost 90 (acquired 2022-01-01); 6 are sold: sheet = purchase 10 + sale 6, final stock 4
        SecurityTransfer in = new SecurityTransfer("i", "B", "acc", d("2024-01-12"), ref(), AAPL, SecurityTransfer.Direction.IN, n("10"),
                d("2022-01-01"), n("90"), "EUR", "X");
        TaxResult r = run(2024, in, sell("2024-06-03", "6", "120"));
        List<SecurityEvent> ev = events(r, 0);
        assertEquals(d("2022-01-01"), ev.get(0).date());
        assertNum("10", ev.get(0).quantity());
        assertTrue(new KdvpXmlWriter().write(r.kdvp()).contains("<F8>4.00000000</F8>"));
    }

    @Test void unpairedOutgoingTransferLeavesFifoOrderAndWarns() {
        // lots 5 @100 (2023-01), 5 @200 (2023-02); 5 leave to an unknown account; selling 5 now uses the @200 lot
        SecurityTransfer out = new SecurityTransfer("o", "A", "acc", d("2023-12-01"), ref(), AAPL, SecurityTransfer.Direction.OUT, n("5"), null, null, null, "X");
        TaxResult r = run(2024, buy("2023-01-01", "5", "100"), buy("2023-02-01", "5", "200"), out, sell("2024-03-01", "5", "250"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNum("250", r.lotMatches().get(0).gainEur()); // 5 * (250 - 200)
        assertTrue(r.diagnostics().stream().anyMatch(x -> x.code().equals("TRANSFER_OUT_UNPAIRED")));
    }

    @Test void unsupportedCorporateActionExcludesOnlyRelevantInstruments() {
        CorporateAction merger = new CorporateAction("m", "A", "acc", d("2023-07-01"), ref(), AAPL, CorporateAction.Type.UNSUPPORTED, null, null, "merger");
        TaxResult relevant = run(2024, buy("2023-01-01", "5", "100"), merger, sell("2024-03-01", "5", "150"));
        assertFalse(relevant.complete());
        assertEquals("UNSUPPORTED_CORPORATE_ACTION", errorCodes(relevant).get(0));
        TaxResult unrelated = run(2024, buy("2023-01-01", "5", "100"), merger); // nothing sold in 2024: not processed
        assertTrue(unrelated.complete());
        assertNull(unrelated.kdvp());
    }

    @Test void laterYearsAreIgnored() {
        TaxResult r = run(2024, buy("2023-01-01", "5", "100"), sell("2024-03-01", "5", "150"), sell("2025-03-01", "99", "150"));
        assertTrue(r.complete(), r.diagnostics().toString());
        assertEquals(1, r.lotMatches().size());
        assertEquals(1, events(r, 0).stream().filter(e -> e.kind() == SecurityEvent.Kind.SALE).count());
    }

    @Test void soldInstrumentWithoutIsinIsRejected() {
        Instrument noIsin = new Instrument(null, "XYZ", "Unknown", Instrument.AssetClass.STOCK);
        TaxResult r = run(2024, trade("A", Trade.Side.BUY, "2023-01-01", "1", "10", "EUR", "0", noIsin),
                trade("A", Trade.Side.SELL, "2024-01-01", "1", "12", "EUR", "0", noIsin));
        assertEquals("MISSING_ISIN", errorCodes(r).get(0));
        assertNull(r.kdvp());
    }

    @Test void missingRateIsAnErrorNotAGuess() {
        // no USD rate within 7 days before 2020-01-02
        TaxResult r = run(2020, trade("A", Trade.Side.BUY, "2019-12-30", "1", "10", "USD", "0", AAPL),
                trade("A", Trade.Side.SELL, "2020-01-02", "1", "12", "USD", "0", AAPL));
        assertEquals("RATE_UNAVAILABLE", errorCodes(r).get(0));
        assertTrue(r.rates().isEmpty());
    }

    @Test void feesLargerThanProceedsAreRejected() {
        TaxResult r = run(2024, buy("2023-01-01", "1", "10"), trade("A", Trade.Side.SELL, "2024-01-01", "1", "1", "EUR", "5", AAPL));
        assertEquals("NEGATIVE_UNIT_VALUE", errorCodes(r).get(0));
    }

    @Test void dividendsAreConvertedPerPaymentAndStaySeparateFromGains() {
        // 12.5 USD gross / 1.25 = 10.00 EUR; 2.5 USD withheld / 1.25 = 2.00 EUR
        DividendPayment div = new DividendPayment("dv", "A", "acc", d("2024-05-15"), ref(), AAPL, n("12.5"), n("2.5"), "USD", "US");
        TaxResult r = run(2024, div);
        assertTrue(r.complete(), r.diagnostics().toString());
        assertNull(r.kdvp());
        assertNum("10", r.div().dividends().get(0).grossValue());
        assertNum("2", r.div().dividends().get(0).foreignTax());
        assertEquals("US", r.div().dividends().get(0).payerCountry());
        assertTrue(r.diagnostics().stream().anyMatch(x -> x.code().equals("PAYER_DETAILS_MISSING")));
        assertXmlValid(r);
    }

    @Test void dividendWithoutCountryIsExcludedWithError() {
        DividendPayment div = new DividendPayment("dv", "A", "acc", d("2024-05-15"), ref(), AAPL, n("1"), n("0"), "EUR", null);
        TaxResult r = run(2024, div);
        assertFalse(r.complete());
        assertNull(r.div());
        assertEquals(List.of("MISSING_COUNTRY", "DIVIDEND_EXCLUDED"), errorCodes(r));
    }

    @Test void dividendsOutsideTheYearAreIgnored() {
        DividendPayment div = new DividendPayment("dv", "A", "acc", d("2023-05-15"), ref(), AAPL, n("1"), n("0"), "EUR", "US");
        assertNull(run(2024, div).div());
    }

    @Test void etfIsMarkedAsFundAndLongSymbolOmitsCode() {
        Instrument etf = new Instrument("IE00BK5BQT80", "VERYLONGSYMBOL", "Vanguard ETF", Instrument.AssetClass.ETF);
        TaxResult r = run(2024, trade("A", Trade.Side.BUY, "2023-01-01", "1", "10", "EUR", "0", etf),
                trade("A", Trade.Side.SELL, "2024-01-01", "1", "12", "EUR", "0", etf));
        Security s = r.kdvp().items().get(0).security();
        assertTrue(s.fund());
        assertNull(s.code());
        assertXmlValid(r);
    }

    @Test void combinedReportIsComplete() {
        DividendPayment div = new DividendPayment("dv", "A", "acc", d("2024-05-15"), ref(), AAPL, n("12.5"), n("2.5"), "USD", "US");
        TaxResult r = run(2024, buy("2023-01-01", "5", "100"), sell("2024-03-01", "5", "150"), div);
        assertTrue(r.complete());
        assertNotNull(r.kdvp());
        assertNotNull(r.div());
        assertXmlValid(r);
        assertEquals(r.diagnostics(), r.audit().diagnostics());
    }
}
