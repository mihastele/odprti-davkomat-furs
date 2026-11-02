package si.opendavki.broker.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.json.Json;
import si.opendavki.ledger.*;
import si.opendavki.model.Taxpayer;
import si.opendavki.tax.AuditReport;
import si.opendavki.tax.TaxEngine;
import si.opendavki.tax.TaxOptions;
import si.opendavki.tax.TaxResult;

/**
 * Tests the profile ENGINE with invented layouts (a Revolut-like one with currency-prefixed numbers and an action column, and a
 * DEGIRO-like one with unnamed currency columns, decimal commas and buy/sell from the quantity's sign). They say nothing about
 * what any real broker's file looks like.
 */
class ProfileAdapterTest {
    private static final String ACTION_PROFILE = """
            {"formatVersion":1,"id":"prefixed-draft","displayName":"Prefixed test layout","status":"DRAFT","note":"test",
             "broker":"testbroker","delimiter":",",
             "requiredColumns":["Date","Ticker","Type","Quantity","Price per share","Total Amount","Currency"],
             "date":{"column":"Date","patterns":["uuuu-MM-dd'T'HH:mm:ss.SSSX","uuuu-MM-dd'T'HH:mm:ssX","uuuu-MM-dd"]},
             "number":{"decimalSeparator":".","groupSeparator":",","currencyAffix":true},
             "instrument":{"isinColumn":null,"symbolColumn":"Ticker","nameColumn":null},
             "action":{"column":"Type","signDetermines":false,"buy":["BUY - MARKET"],"sell":["SELL - MARKET"],"dividend":["DIVIDEND"],
                       "skip":["CASH TOP-UP"],"unsupported":["STOCK SPLIT"]},
             "trade":{"quantityColumn":"Quantity","priceColumn":"Price per share","currencyColumn":"Currency","feeColumn":null,
                      "feeCurrencyColumn":null,"feeCurrency":null,"totalColumn":"Total Amount","totalCheck":"QTY_PRICE"},
             "dividend":{"amountIs":"NET","grossColumn":null,"withholdingColumn":null,"currencyColumn":"Currency"}}""";

    private static final String SIGN_PROFILE = """
            {"formatVersion":1,"id":"signed","displayName":"Signed test layout","status":"VERIFIED","broker":"testbroker2","delimiter":",",
             "requiredColumns":["Date","Product","ISIN","Quantity","Price","Order ID"],
             "date":{"column":"Date","patterns":["dd-MM-uuuu"]},
             "number":{"decimalSeparator":",","groupSeparator":".","currencyAffix":false},
             "instrument":{"isinColumn":"ISIN","symbolColumn":"Product","nameColumn":"Product"},
             "action":{"column":null,"signDetermines":true,"buy":[],"sell":[],"dividend":[],"skip":[],"unsupported":[]},
             "trade":{"quantityColumn":"Quantity","priceColumn":"Price","currencyColumn":"after:Price","feeColumn":"Fees EUR",
                      "feeCurrencyColumn":null,"feeCurrency":"EUR","totalColumn":null,"totalCheck":"NONE"},
             "dividend":null}""";

    private static final String GROSS_PROFILE = """
            {"formatVersion":1,"id":"gross-draft","displayName":"Gross dividends layout","status":"DRAFT","broker":"testbroker3","delimiter":";",
             "requiredColumns":["Date","ISIN","Type","Quantity","Price","Currency","Gross","Tax"],
             "date":{"column":"Date","patterns":["uuuu-MM-dd"]},"number":{"decimalSeparator":".","groupSeparator":"","currencyAffix":false},
             "instrument":{"isinColumn":"ISIN","symbolColumn":null,"nameColumn":null},
             "action":{"column":"Type","signDetermines":false,"buy":["B"],"sell":["S"],"dividend":["D"],"skip":[],"unsupported":[]},
             "trade":{"quantityColumn":"Quantity","priceColumn":"Price","currencyColumn":"Currency","feeColumn":null,"feeCurrencyColumn":null,"feeCurrency":null,"totalColumn":null,"totalCheck":"NONE"},
             "dividend":{"amountIs":"GROSS","grossColumn":"Gross","withholdingColumn":"Tax","currencyColumn":"Currency"}}""";

    private static ProfileAdapter adapter(String json, Path dir) throws Exception {
        Path f = dir.resolve("p.json");
        Files.writeString(f, json);
        return new ProfileAdapter(ProfileLoader.load(f));
    }

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private static void assertNum(String expected, BigDecimal actual) {
        assertEquals(0, n(expected).compareTo(actual), "expected " + expected + " but was " + actual.toPlainString());
    }

    private static List<String> codes(ImportResult r) { return r.diagnostics().stream().map(Diagnostic::code).toList(); }

    private static final String HEAD = "Date,Ticker,Type,Quantity,Price per share,Total Amount,Currency\n";

    @Test void actionColumnLayoutMapsBuysSellsAndSkipsWithReasons(@TempDir Path dir) throws Exception {
        ProfileAdapter a = adapter(ACTION_PROFILE, dir);
        ImportResult r = a.read(StatementSource.ofText("s.csv", HEAD
                + "2024-03-05T14:30:51.123Z,AAPL,BUY - MARKET,2.5,USD 190.40,USD 476.00,USD\n"          // line 2: 2.5 x 190.40 = 476.00
                + "2024-04-01T10:00:00Z,AAPL,SELL - MARKET,1,\"USD 1,200.50\",\"USD 1,200.50\",USD\n"    // line 3: group separator
                + "2024-04-02,AAPL,DIVIDEND,,,USD 0.85,USD\n"                                             // line 4
                + "2024-04-03,AAPL,STOCK SPLIT,,,,USD\n"                                                  // line 5
                + "2024-04-04,,CASH TOP-UP,,,USD 100,USD\n"                                               // line 6
                + "2024-04-05,AAPL,WEIRD,1,USD 1,USD 1,USD\n"));                                          // line 7
        assertEquals(6, r.stats().rowsRead()); // lines 2 to 7
        assertEquals(2, r.stats().rowsMapped());
        assertEquals(4, r.stats().rowsSkipped());
        Trade buy = (Trade) r.entries().get(0);
        assertEquals(Trade.Side.BUY, buy.side());
        assertEquals("2024-03-05", buy.date().toString());
        assertNum("2.5", buy.quantity());
        assertNum("190.40", buy.price());
        assertEquals("USD", buy.currency());
        assertEquals("testbroker", buy.broker());
        assertNull(buy.instrument().isin());
        assertEquals("AAPL", buy.instrument().symbol());
        assertNum("1200.50", ((Trade) r.entries().get(1)).price());
        assertEquals(List.of("DRAFT_IMPORTER", "UNSUPPORTED_DIVIDEND", "UNSUPPORTED_ACTION", "SKIPPED_ACTION", "UNKNOWN_ACTION"), codes(r));
        assertFalse(r.hasErrors());
    }

    @Test void badRowsFailWithSpecificCodes(@TempDir Path dir) throws Exception {
        ImportResult r = adapter(ACTION_PROFILE, dir).read(StatementSource.ofText("s.csv", HEAD
                + "2024-03-05,AAPL,BUY - MARKET,1,EUR 10,EUR 10,USD\n"       // price written in EUR on a USD row
                + "2024-03-05,AAPL,BUY - MARKET,1,USD 1.2.3,USD 1,USD\n"     // two decimal separators
                + "2024-03-05,AAPL,BUY - MARKET,1,\"USD 1,23\",USD 1,USD\n"   // 1,23 : comma is the group separator, but not in groups of 3
                + "2024-13-01,AAPL,BUY - MARKET,1,USD 1,USD 1,USD\n"         // month 13
                + "2024-03-05,AAPL,BUY - MARKET,0,USD 1,USD 1,USD\n"         // zero quantity
                + "2024-03-05,,BUY - MARKET,1,USD 1,USD 1,USD\n"             // no instrument
                + "2024-03-05,AAPL,BUY - MARKET\n"));                        // short row
        assertEquals(List.of("DRAFT_IMPORTER", "CURRENCY_MISMATCH", "INVALID_NUMBER", "INVALID_NUMBER", "INVALID_DATE", "INVALID_QUANTITY", "MISSING_INSTRUMENT", "COLUMN_COUNT"), codes(r));
        assertEquals(7, r.stats().rowsFailed());
        assertEquals(0, r.entries().size());
    }

    @Test void totalPlausibilityWarnsButKeepsTheEntry(@TempDir Path dir) throws Exception {
        ImportResult r = adapter(ACTION_PROFILE, dir).read(StatementSource.ofText("s.csv", HEAD + "2024-03-05,AAPL,BUY - MARKET,1,USD 100,USD 150,USD\n"));
        assertEquals(1, r.entries().size());
        assertTrue(codes(r).contains("TOTAL_MISMATCH"));
        assertTrue(r.diagnostics().stream().filter(d -> d.code().equals("TOTAL_MISMATCH")).findFirst().orElseThrow().message().contains("100.00"));
    }

    private static final String SIGN_HEAD = "Date,Time,Product,ISIN,Reference exchange,Venue,Quantity,Price,,Local value,,Fees EUR,,Order ID\n";

    @Test void unnamedCurrencyColumnsDecimalCommasAndSignedQuantities(@TempDir Path dir) throws Exception {
        ProfileAdapter a = adapter(SIGN_PROFILE, dir);
        ImportResult r = a.read(StatementSource.ofText("s.csv", SIGN_HEAD
                + "05-03-2024,09:00,VANGUARD S&P 500,IE00B3XXRP09,XAMS,XAMS,3,\"67,57\",EUR,\"-202,71\",EUR,\"-1,00\",EUR,id-1\n"
                + "06-03-2024,09:00,VANGUARD S&P 500,IE00B3XXRP09,XAMS,XAMS,-2,\"1.070,10\",USD,\"2.140,20\",USD,\"-2,50\",EUR,id-2\n"));
        assertEquals(2, r.entries().size());
        Trade buy = (Trade) r.entries().get(0), sell = (Trade) r.entries().get(1);
        assertEquals(Trade.Side.BUY, buy.side());
        assertNum("67.57", buy.price());
        assertEquals("EUR", buy.currency());
        assertNum("1.00", buy.fee());
        assertEquals("EUR", buy.feeCurrency());
        assertEquals(Trade.Side.SELL, sell.side());
        assertNum("2", sell.quantity());
        assertNum("1070.10", sell.price()); // thousands separator '.' honoured
        assertEquals("USD", sell.currency());
        assertEquals("IE00B3XXRP09", sell.instrument().isin());
        assertEquals("2024-03-06", sell.date().toString());
        assertFalse(codes(r).contains("DRAFT_IMPORTER")); // VERIFIED profile
    }

    @Test void aDotInADecimalCommaFileIsRejectedNotMisreadAsThousands(@TempDir Path dir) throws Exception {
        ImportResult r = adapter(SIGN_PROFILE, dir).read(StatementSource.ofText("s.csv", SIGN_HEAD
                + "05-03-2024,09:00,X,IE00B3XXRP09,XAMS,XAMS,3,67.57,EUR,\"-202,71\",EUR,\"-1,00\",EUR,id-1\n"));
        assertEquals(List.of("INVALID_NUMBER"), codes(r));
        assertEquals(0, r.entries().size());
    }

    @Test void grossDividendLayoutProducesDividendEntries(@TempDir Path dir) throws Exception {
        ImportResult r = adapter(GROSS_PROFILE, dir).read(StatementSource.ofText("s.csv",
                "Date;ISIN;Type;Quantity;Price;Currency;Gross;Tax\n2024-05-15;US0378331005;D;;;USD;12.50;1.88\n2024-05-16;US0378331005;B;1;10;USD;;\n"));
        DividendPayment d = (DividendPayment) r.entries().get(0);
        assertNum("12.50", d.gross());
        assertNum("1.88", d.withholdingTax());
        assertEquals("US", d.sourceCountry());
        assertEquals("USD", d.currency());
        assertEquals(2, r.entries().size());
    }

    @Test void headerMismatchIsExplainedWithTheActualHeader(@TempDir Path dir) throws Exception {
        ProfileAdapter a = adapter(ACTION_PROFILE, dir);
        StatementSource wrong = StatementSource.ofText("w.csv", "Date,Symbol,Kind,Qty\n2024-01-01,A,B,1\n");
        assertFalse(a.accepts(wrong));
        ImportResult r = a.read(wrong);
        assertTrue(r.hasErrors());
        String msg = r.diagnostics().get(0).message();
        assertTrue(msg.contains("missing columns") && msg.contains("Ticker") && msg.contains("[Date, Symbol, Kind, Qty]"), msg);
        assertTrue(a.accepts(StatementSource.ofText("ok.csv", HEAD)));
    }

    @Test void profilesAreValidatedWhenLoaded(@TempDir Path dir) throws Exception {
        assertThrows(IllegalArgumentException.class, () -> adapter(ACTION_PROFILE.replace("\"prefixed-draft\"", "\"prefixed\""), dir)); // DRAFT needs -draft
        assertThrows(IllegalArgumentException.class, () -> adapter(SIGN_PROFILE.replace("\"signed\"", "\"signed-draft\""), dir));          // VERIFIED must not
        assertThrows(IllegalArgumentException.class, () -> adapter(ACTION_PROFILE.replace("\"formatVersion\":1", "\"formatVersion\":2"), dir));
        assertThrows(IllegalArgumentException.class, () -> adapter(ACTION_PROFILE.replace("\"broker\":\"testbroker\",", ""), dir));
        assertThrows(IllegalArgumentException.class, () -> adapter(ACTION_PROFILE.replace("\"decimalSeparator\":\".\"", "\"decimalSeparator\":\";\""), dir));
        assertThrows(IllegalArgumentException.class, () -> adapter(ACTION_PROFILE.replace("\"patterns\":[", "\"typo\":1,\"patterns\":["), dir)); // unknown property
        assertThrows(IllegalArgumentException.class, () -> adapter("not json", dir));
        String message = assertThrows(IllegalArgumentException.class, () -> adapter(ACTION_PROFILE.replace("\"prefixed-draft\"", "\"Bad Id\""), dir)).getMessage();
        assertTrue(message.contains("p.json"), message);
    }

    /** A draft profile's entries end up in a report that says so, in the diagnostics and in the audit banner. */
    @Test void draftDataIsCalledOutInTheReportAndTheAudit(@TempDir Path dir) throws Exception {
        ImportResult r = adapter(ACTION_PROFILE, dir).read(StatementSource.ofText("s.csv", HEAD
                + "2023-03-01,AAPL,BUY - MARKET,10,USD 100,USD 1000,USD\n2024-02-01,AAPL,SELL - MARKET,4,USD 130,USD 520,USD\n"));
        List<LedgerEntry> eur = r.entries().stream().map(e -> e.mapInstruments(i -> new Instrument("US0378331005", i.symbol(), null, i.assetClass()))).toList();
        Path rates = dir.resolve("rates.csv");
        Files.writeString(rates, "date,currency,rate\n2023-03-01,USD,1\n2024-02-01,USD,1\n");
        FxService fx = new FxService(FxConfig.from(java.util.Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", rates.toString())));
        TaxResult result = new TaxEngine(fx, TaxOptions.defaults()).compute(Ledger.of(eur), 2024, new Taxpayer("12345678", "FO"));
        assertTrue(result.complete(), result.diagnostics().toString());
        assertNum("120", result.lotMatches().get(0).gainEur()); // 4 x (130 - 100) at rate 1
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("DRAFT_SOURCE_USED") && d.message().contains("prefixed-draft")));
        assertTrue(AuditReport.markdown(result.audit()).contains("Contains data from DRAFT importers"));
        // and a profile that is VERIFIED leaves no such trace
        ImportResult verified = adapter(SIGN_PROFILE, dir).read(StatementSource.ofText("v.csv", SIGN_HEAD
                + "01-03-2023,09:00,X,US0378331005,XAMS,XAMS,10,\"100,00\",USD,\"-1000,00\",USD,\"0,00\",EUR,a\n01-02-2024,09:00,X,US0378331005,XAMS,XAMS,-4,\"130,00\",USD,\"520,00\",USD,\"0,00\",EUR,b\n"));
        TaxResult clean = new TaxEngine(fx, TaxOptions.defaults()).compute(Ledger.of(verified.entries()), 2024, new Taxpayer("12345678", "FO"));
        assertTrue(clean.diagnostics().stream().noneMatch(d -> d.code().equals("DRAFT_SOURCE_USED")), clean.diagnostics().toString());
        assertFalse(AuditReport.markdown(clean.audit()).contains("DRAFT importers"));
    }

    @Test void profileJsonRoundTrips(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("p.json");
        Files.writeString(f, ACTION_PROFILE);
        Profile p = ProfileLoader.load(f);
        assertEquals(p, Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(p), Profile.class));
    }
}
