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

class LedgerOverridesTest {
    private static final Instrument AAPL = new Instrument("US0378331005", "AAPL", "Apple", Instrument.AssetClass.STOCK);

    private static CorporateAction unsupported(String id, String broker) {
        return new CorporateAction(id, broker, "acc", LocalDate.parse("2024-01-01"), new SourceRef("t", "t.csv", "00", 5), AAPL,
                CorporateAction.Type.UNSUPPORTED, null, null, "spin-off");
    }

    private static Trade buy(String id, String broker) {
        return new Trade(id, broker, "acc", LocalDate.parse("2023-01-01"), new SourceRef("t", "t.csv", "00", 3), AAPL, Trade.Side.BUY,
                BigDecimal.ONE, BigDecimal.TEN, "EUR", null, null);
    }

    @Test void ignoredEntryIsRemovedAndReportedWithItsReason() {
        Ledger l = Ledger.of(List.of(buy("b1", "A"), unsupported("ca1", "A")));
        LedgerOverrides.Result r = LedgerOverrides.parse("id,reason\nca1,Split entered by hand in corrections.csv\n", "o.csv").apply(l);
        assertEquals(1, r.ledger().entries().size());
        assertEquals("b1", r.ledger().entries().get(0).id());
        assertEquals(1, r.diagnostics().size());
        Diagnostic d = r.diagnostics().get(0);
        assertEquals("ENTRY_IGNORED", d.code());
        assertEquals(Diagnostic.Severity.WARNING, d.severity()); // ignoring must stay visible
        assertTrue(d.message().contains("Split entered by hand"), d.message());
        assertEquals("t.csv:5", d.source().toString());
    }

    @Test void aReasonIsMandatory() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> LedgerOverrides.parse("id,reason\nca1,\n", "o.csv")).getMessage().contains("o.csv:2"));
        assertThrows(IllegalArgumentException.class, () -> LedgerOverrides.parse("id\nca1\n", "o.csv"));
        assertThrows(IllegalArgumentException.class, () -> LedgerOverrides.parse("id,action,reason\nca1,DELETE,x\n", "o.csv"));
        assertThrows(IllegalArgumentException.class, () -> LedgerOverrides.parse("id,reason\n,why\n", "o.csv"));
    }

    @Test void unknownIdsAreWarnedAboutNotSilentlyAccepted() {
        LedgerOverrides.Result r = LedgerOverrides.parse("id,reason\nnope,stale\n", "o.csv").apply(Ledger.of(List.of(buy("b1", "A"))));
        assertEquals(1, r.ledger().entries().size());
        assertEquals("OVERRIDE_UNUSED", r.diagnostics().get(0).code());
    }

    @Test void sameIdAtSeveralBrokersNeedsTheBrokerColumn() {
        Ledger l = Ledger.of(List.of(unsupported("x", "A"), unsupported("x", "B")));
        LedgerOverrides.Result ambiguous = LedgerOverrides.parse("id,reason\nx,dup\n", "o.csv").apply(l);
        assertEquals(2, ambiguous.ledger().entries().size());
        assertEquals("OVERRIDE_AMBIGUOUS", ambiguous.diagnostics().get(0).code());
        assertEquals(Diagnostic.Severity.ERROR, ambiguous.diagnostics().get(0).severity());
        LedgerOverrides.Result precise = LedgerOverrides.parse("id,broker,reason\nx,B,dup\n", "o.csv").apply(l);
        assertEquals(1, precise.ledger().entries().size());
        assertEquals("A", precise.ledger().entries().get(0).broker());
    }

    @Test void applyingTwiceIsHarmlessButTheSecondRunSaysTheRowIsUnused() {
        LedgerOverrides o = LedgerOverrides.parse("id,reason\nca1,done\n", "o.csv");
        Ledger once = o.apply(Ledger.of(List.of(unsupported("ca1", "A")))).ledger();
        assertEquals("OVERRIDE_UNUSED", o.apply(once).diagnostics().get(0).code());
        assertEquals(0, o.apply(once).ledger().entries().size());
    }

    @Test void configuredThroughTheEnvironment(@TempDir Path dir) throws Exception {
        assertTrue(LedgerOverrides.fromEnvironment(Map.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> LedgerOverrides.fromEnvironment(Map.of("OPENDAVKI_OVERRIDES_FILE", dir.resolve("none.csv").toString())));
        Path f = dir.resolve("o.csv");
        Files.writeString(f, "# why: example\nid,reason\nca1,done\n");
        assertTrue(LedgerOverrides.fromEnvironment(Map.of("OPENDAVKI_OVERRIDES_FILE", f.toString())).isPresent());
    }
}
