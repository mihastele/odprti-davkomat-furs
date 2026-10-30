package si.opendavki.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.model.Taxpayer;
import si.opendavki.tax.TaxEngine;
import si.opendavki.tax.TaxOptions;
import si.opendavki.tax.TaxResult;

class InstrumentMasterTest {
    private static final Instrument NO_ISIN = new Instrument(null, "SAPX", "SAP via CUSIP", Instrument.AssetClass.STOCK);
    private static final Instrument WITH_ISIN = new Instrument("IE00B4ND3602", "IGLN", "iShares Physical Gold", Instrument.AssetClass.STOCK);
    private int line;

    private Trade trade(Instrument ins, Trade.Side side, String date) {
        return new Trade("t" + (++line), "A", "acc", LocalDate.parse(date), new SourceRef("test", "t.csv", "00", line), ins, side,
                new BigDecimal("2"), new BigDecimal("10"), "EUR", null, null);
    }

    private Ledger ledger(Instrument ins) {
        return Ledger.of(List.of(trade(ins, Trade.Side.BUY, "2023-01-01"), trade(ins, Trade.Side.SELL, "2024-01-01")));
    }

    @Test void missingIsinIsSuppliedBySymbol() {
        InstrumentMaster m = InstrumentMaster.parse("match,isin\nsapx,DE0007164600\n", "m.csv");
        InstrumentMaster.Result r = m.apply(ledger(NO_ISIN));
        assertEquals("DE0007164600", r.ledger().entries().get(0).instrument().isin());
        assertEquals("SAPX", r.ledger().entries().get(0).instrument().symbol());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("INSTRUMENT_MASTER_APPLIED")));
        assertTrue(LedgerChecker.check(ledger(NO_ISIN)).stream().anyMatch(d -> d.code().equals("MISSING_ISIN")));
        assertTrue(LedgerChecker.check(r.ledger()).stream().noneMatch(d -> d.code().equals("MISSING_ISIN")));
    }

    @Test void assetClassOverrideDecidesTheFundFlagOnTheSheet() {
        Ledger raw = ledger(WITH_ISIN);
        FxService fx = new FxService(FxConfig.defaults());
        TaxResult before = new TaxEngine(fx, TaxOptions.defaults()).compute(raw, 2024, new Taxpayer("12345678", "FO"));
        assertFalse(before.kdvp().items().get(0).security().fund());
        Ledger fixed = InstrumentMaster.parse("match,asset_class,name\nIE00B4ND3602,etf,iShares Physical Gold ETC\n", "m.csv").apply(raw).ledger();
        TaxResult after = new TaxEngine(fx, TaxOptions.defaults()).compute(fixed, 2024, new Taxpayer("12345678", "FO"));
        assertTrue(after.kdvp().items().get(0).security().fund());
        assertEquals("iShares Physical Gold ETC", after.kdvp().items().get(0).security().name());
    }

    @Test void aContradictingIsinIsReportedAndNotApplied() {
        InstrumentMaster m = InstrumentMaster.parse("match,isin,asset_class\nIE00B4ND3602,IE00B4NCWG09,ETF\n", "m.csv");
        InstrumentMaster.Result r = m.apply(ledger(WITH_ISIN));
        assertEquals("IE00B4ND3602", r.ledger().entries().get(0).instrument().isin());
        assertEquals(Instrument.AssetClass.STOCK, r.ledger().entries().get(0).instrument().assetClass()); // whole row ignored
        assertEquals("INSTRUMENT_MASTER_CONFLICT", r.diagnostics().get(0).code());
    }

    @Test void unmatchedRowsAreReportedAndSymbolRowsDoNotTouchInstrumentsThatHaveAnIsin() {
        InstrumentMaster m = InstrumentMaster.parse("# comment\nmatch,isin\nIGLN,IE00B4NCWG09\nNOPE,DE0007164600\n", "m.csv");
        InstrumentMaster.Result r = m.apply(ledger(WITH_ISIN));
        assertEquals("IE00B4ND3602", r.ledger().entries().get(0).instrument().isin()); // a symbol only matches instruments without ISIN
        assertEquals(2, r.diagnostics().stream().filter(d -> d.code().equals("INSTRUMENT_MASTER_UNUSED")).count());
    }

    @Test void conversionTargetsAreCorrectedToo() {
        Instrument target = new Instrument(null, "NEWX", "New", Instrument.AssetClass.STOCK);
        CorporateAction c = new CorporateAction("c", "A", "acc", LocalDate.parse("2024-01-01"), new SourceRef("t", "t.csv", "00", 9), WITH_ISIN,
                CorporateAction.Type.CONVERSION, BigDecimal.ONE, BigDecimal.ONE, "x", target);
        Ledger l = InstrumentMaster.parse("match,isin\nNEWX,DE0007164600\n", "m.csv").apply(Ledger.of(List.of(c))).ledger();
        assertEquals("DE0007164600", ((CorporateAction) l.entries().get(0)).target().isin());
    }

    @Test void badFilesAreRejectedWithTheirLine() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> InstrumentMaster.parse("isin\nIE00B4ND3602\n", "m.csv")).getMessage().contains("'match'"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> InstrumentMaster.parse("match,isin\nX,notanisin\n", "m.csv")).getMessage().contains("m.csv:2"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> InstrumentMaster.parse("match,asset_class\nX,bond\n", "m.csv")).getMessage().contains("asset_class"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> InstrumentMaster.parse("match,isin\n,IE00B4ND3602\n", "m.csv")).getMessage().contains("empty"));
    }

    @Test void configuredThroughTheEnvironment(@TempDir Path dir) throws Exception {
        assertTrue(InstrumentMaster.fromEnvironment(Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> InstrumentMaster.fromEnvironment(Map.of("OPENDAVKI_INSTRUMENTS_FILE", dir.resolve("missing.csv").toString())));
        Path f = dir.resolve("instruments.csv");
        Files.writeString(f, "match,isin\nSAPX,DE0007164600\n");
        InstrumentMaster m = InstrumentMaster.fromEnvironment(Map.of("OPENDAVKI_INSTRUMENTS_FILE", f.toString())).orElseThrow();
        assertEquals("DE0007164600", m.apply(ledger(NO_ISIN)).ledger().entries().get(0).instrument().isin());
    }

    @Test void applyingTwiceChangesNothingMore() {
        InstrumentMaster m = InstrumentMaster.parse("match,isin,asset_class\nSAPX,DE0007164600,ETF\n", "m.csv");
        Ledger once = m.apply(ledger(NO_ISIN)).ledger();
        InstrumentMaster.Result twice = m.apply(once);
        assertEquals(once, twice.ledger());
        assertTrue(twice.diagnostics().stream().noneMatch(d -> d.code().equals("INSTRUMENT_MASTER_APPLIED")));
    }
}
