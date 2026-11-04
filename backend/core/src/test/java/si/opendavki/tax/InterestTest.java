package si.opendavki.tax;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import si.opendavki.furs.FormType;
import si.opendavki.furs.ObrXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.ledger.*;
import si.opendavki.model.Taxpayer;

/** Interest (Doh-Obr). USD rates in tax-rates.csv: 1.10 on 2024-03-01 so 11.00 USD = 10.00 EUR and 1.10 USD = 1.00 EUR. */
class InterestTest {
    private static final Taxpayer TP = new Taxpayer("12345678", "FO");
    private int seq;

    private InterestPayment pay(String date, String gross, String wht, String ccy, String payer) {
        return new InterestPayment("i" + ++seq, "B", "acc", LocalDate.parse(date), new SourceRef("test", "t.csv", "00", seq),
                new BigDecimal(gross), new BigDecimal(wht), ccy, payer, null, "interest");
    }

    private TaxResult run(TaxOptions o, int year, InterestPayment... e) {
        FxService fx = new FxService(FxConfig.from(Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_FX_FILE", "src/test/resources/fx/tax-rates.csv")));
        return new TaxEngine(fx, o).compute(Ledger.of(List.of(e)), year, TP);
    }

    private static List<String> codes(TaxResult r) { return r.diagnostics().stream().map(Diagnostic::code).toList(); }

    @Test void convertsAtPaymentDateAndKeepsOnlyTheYear() {
        TaxResult r = run(new TaxOptions(true, "1"), 2024,
                pay("2024-03-01", "11.00", "1.10", "USD", "Broker X"),
                pay("2024-04-01", "5.55", "0", "EUR", "Broker X"),
                pay("2023-03-01", "99", "0", "USD", "Broker X")); // other year
        assertTrue(r.complete(), r.diagnostics().toString());
        var rows = r.obr().interest();
        assertEquals(2, rows.size());
        assertEquals(0, new BigDecimal("10").compareTo(rows.get(0).value()));
        assertEquals(0, new BigDecimal("1").compareTo(rows.get(0).foreignTax()));
        assertEquals(0, new BigDecimal("5.55").compareTo(rows.get(1).value()));
        assertEquals("1", rows.get(0).type());
        assertFalse(codes(r).contains("INTEREST_TYPE_CODE_UNSET"));
    }

    @Test void unsetTypeCodeAndMissingPayerDetailsWarn() {
        TaxResult r = run(TaxOptions.defaults(), 2024, pay("2024-04-01", "5.55", "0", "EUR", "Broker X"));
        assertTrue(codes(r).contains("INTEREST_TYPE_CODE_UNSET"));
        assertTrue(codes(r).contains("INTEREST_PAYER_DETAILS_MISSING"));
        assertNull(r.obr().interest().get(0).type());
    }

    @Test void missingRateExcludesTheRowAndMarksResultIncomplete() {
        TaxResult r = run(TaxOptions.defaults(), 2022, pay("2022-01-04", "5", "0", "USD", "Broker X"));
        assertFalse(r.complete());
        assertTrue(codes(r).contains("INTEREST_EXCLUDED"));
    }

    @Test void generatedObrXmlValidatesAgainstTheOfficialSchema() {
        TaxResult r = run(new TaxOptions(true, "1"), 2024, pay("2024-03-01", "11.00", "1.10", "USD", "Broker X"));
        var res = new SchemaValidator(Path.of("../../schemas")).validate(FormType.OBR, new ObrXmlWriter().write(r.obr()));
        assertTrue(res.valid(), res.toString());
    }
}
