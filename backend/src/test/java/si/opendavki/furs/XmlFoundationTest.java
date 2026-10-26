package si.opendavki.furs;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.opendavki.model.*;

/** Validates against the official FURS XSDs in /schemas. Schema-valid is necessary, not sufficient, for tax-correctness. */
class XmlFoundationTest {
    private static final Path SCHEMAS = Path.of("../schemas");
    private final SchemaValidator validator = new SchemaValidator(SCHEMAS);
    private final Taxpayer tp = new Taxpayer("12345678", "FO");

    private static SecurityEvent buy(String d, String q, String p) {
        return new SecurityEvent(SecurityEvent.Kind.PURCHASE, LocalDate.parse(d), new BigDecimal(q), new BigDecimal(p), "B", "t");
    }
    private static SecurityEvent sell(String d, String q, String p) {
        return new SecurityEvent(SecurityEvent.Kind.SALE, LocalDate.parse(d), new BigDecimal(q), new BigDecimal(p), null, "t");
    }
    private KdvpReport kdvp(SecurityEvent... ev) {
        return new KdvpReport(tp, 2025, true, List.of(KdvpItem.ofSecurity(SheetType.PLVP,
                new Security("US0378331005", "AAPL", "Apple Inc.", false, List.of(ev)))));
    }

    @Test void kdvpIsValidAndRunningBalanceIsDerived() {
        String xml = new KdvpXmlWriter().write(kdvp(buy("2023-03-01", "10", "140.5"), sell("2025-02-14", "4.5", "210.1")));
        assertTrue(validator.validate(FormType.KDVP, xml).valid(), xml);
        assertTrue(xml.contains("<F8>5.50000000</F8>"), xml);
        assertTrue(xml.contains("<F4>140.50000000</F4>"));
    }

    @Test void sameDayPurchaseSortsBeforeSale() {
        String xml = new KdvpXmlWriter().write(kdvp(sell("2025-01-02", "1", "10"), buy("2025-01-02", "1", "9")));
        assertTrue(xml.indexOf("<Purchase>") < xml.indexOf("<Sale>"));
    }

    @Test void oversellIsRejectedWithSourceRef() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new KdvpXmlWriter().write(kdvp(buy("2023-03-01", "1", "1"), sell("2025-02-14", "2", "1"))));
        assertTrue(ex.getMessage().contains("exceeds held quantity"));
    }

    @Test void divIsValidAndKeepsGrossAndForeignTaxSeparate() {
        var d = new Dividend(LocalDate.parse("2025-05-15"), null, "Apple Inc.", "Cupertino", "US", "1",
                new BigDecimal("12.345"), new BigDecimal("1.85"), "US", null, "t");
        String xml = new DivXmlWriter().write(new DivReport(tp, 2025, List.of(d)));
        assertTrue(validator.validate(FormType.DIV, xml).valid(), xml);
        assertTrue(xml.contains("<Value>12.35</Value>"));
        assertTrue(xml.contains("<ForeignTax>1.85</ForeignTax>"));
    }

    @Test void invalidXmlReportsLocatedIssues() {
        String xml = new KdvpXmlWriter().write(kdvp(buy("2023-03-01", "1", "1"))).replace("<IsResident>true", "<IsResident>maybe");
        var r = validator.validate(FormType.KDVP, xml);
        assertFalse(r.valid());
        assertTrue(r.issues().get(0).line() > 0);
    }

    @Test void missingSchemaFailsLoudly() {
        assertThrows(SchemaValidator.SchemaMissingException.class,
                () -> new SchemaValidator(Path.of("does-not-exist")).validate(FormType.KDVP, "<a/>"));
    }

    @Test void divBodyHasNoBodyContentAndDividendsAreOutsideDohDiv() {
        var d = new Dividend(LocalDate.parse("2025-05-15"), null, "X", "Y", "US", "1",
                BigDecimal.ONE, BigDecimal.ZERO, "US", null, "t");
        String xml = new DivXmlWriter().write(new DivReport(tp, 2025, List.of(d)));
        assertFalse(xml.contains("bodyContent"));
        assertTrue(xml.indexOf("</Doh_Div>") < xml.indexOf("<Dividend>") || xml.contains("<Doh_Div/>"));
    }

    @Test void schemaRejectsNegativeQuantity() {
        String xml = new KdvpXmlWriter().write(kdvp(buy("2023-03-01", "1", "1"))).replace("<F3>1.00000000", "<F3>-1.00000000");
        assertFalse(validator.validate(FormType.KDVP, xml).valid());
    }

    @Test void badTaxNumberRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Taxpayer("123", "FO"));
    }
}
