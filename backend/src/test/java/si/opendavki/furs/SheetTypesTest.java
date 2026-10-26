package si.opendavki.furs;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.opendavki.model.*;
import si.opendavki.model.SecurityEvent.Kind;

/** Every Doh-KDVP sheet type and Doh-Div block we can emit must validate against the official XSDs. */
class SheetTypesTest {
    private final SchemaValidator validator = new SchemaValidator(Path.of("../schemas"));
    private final Taxpayer tp = new Taxpayer("12345678", "FO");

    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static LocalDate d(String s) { return LocalDate.parse(s); }

    private void assertValid(FormType f, String xml) {
        var r = validator.validate(f, xml);
        assertTrue(r.valid(), r.issues() + "\n" + xml);
    }

    private Security sec(String isin, String exchange, String resolution, SecurityEvent... ev) {
        return new Security(isin, "SYM", "Name " + isin, false, exchange, resolution,
                resolution == null ? null : d("2024-01-01"), List.of(ev));
    }

    private static SecurityEvent buy(String date, String q, String p) {
        return new SecurityEvent(Kind.PURCHASE, d(date), n(q), n(p), "B", "t");
    }
    private static SecurityEvent sell(String date, String q, String p) {
        return new SecurityEvent(Kind.SALE, d(date), n(q), n(p), null, "t");
    }

    @Test void fullSheetWithF5F10F11AndForeignTax() {
        var purchase = new SecurityEvent(Kind.PURCHASE, d("2022-01-10"), n("10"), n("5"), "F", n("12.5"), n("4.5"), null, "t");
        var sale = new SecurityEvent(Kind.SALE, d("2025-03-01"), n("4"), n("9"), null, null, null, true, "t");
        var item = new KdvpItem(SheetType.PLVP, null, true, n("3.25"), "840", "ZDA", true, false, true, false,
                sec("US0378331005", null, "RES-1", purchase, sale), null, null);
        String xml = new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(item)));
        assertValid(FormType.KDVP, xml);
        assertTrue(xml.contains("<F5>12.5000</F5>") && xml.contains("<F11>4.50000000</F11>") && xml.contains("<F10>true</F10>"));
        assertTrue(xml.contains("<ForeignTax>3.2500</ForeignTax>") && xml.contains("<FTCountryID>840</FTCountryID>"));
        assertTrue(xml.contains("<SecurityCount>1</SecurityCount>"));
    }

    @Test void shortSheet() {
        var item = KdvpItem.ofSecurity(SheetType.PLVPSHORT, sec("US0378331005", null, null, buy("2024-01-02", "3", "10"), sell("2025-01-02", "1", "11")));
        String xml = new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(item)));
        assertValid(FormType.KDVP, xml);
        assertTrue(xml.contains("<SecuritiesShort>") && xml.contains("<SecurityShortCount>1</SecurityShortCount>"));
    }

    @Test void shortSheetRejectsFieldsItCannotExpress() {
        var sale = new SecurityEvent(Kind.SALE, d("2025-01-02"), n("1"), n("11"), null, null, null, true, "t");
        var item = KdvpItem.ofSecurity(SheetType.PLVPSHORT, sec("US0378331005", null, null, buy("2024-01-02", "3", "10"), sale));
        assertThrows(IllegalArgumentException.class, () -> new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(item))));
    }

    @Test void contractSheetsFullAndShort() {
        var full = KdvpItem.ofSecurity(SheetType.PLVPGB, sec("US0378331005", "Broker d.d.", "R", buy("2024-01-02", "3", "10"), sell("2025-01-02", "1", "11")));
        var shrt = KdvpItem.ofSecurity(SheetType.PLVPGBSHORT, sec("US5949181045", "Broker d.d.", null, buy("2024-01-02", "3", "10")));
        String xml = new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(full, shrt)));
        assertValid(FormType.KDVP, xml);
        assertTrue(xml.contains("<SecuritiesWithContract>") && xml.contains("<SecuritiesWithContractShort>"));
        assertTrue(xml.contains("<StockExchangeName>Broker d.d.</StockExchangeName>"));
    }

    @Test void stockExchangeNameRejectedOnPlainSheet() {
        var item = KdvpItem.ofSecurity(SheetType.PLVP, sec("US0378331005", "Broker d.d.", null, buy("2024-01-02", "3", "10")));
        assertThrows(IllegalArgumentException.class, () -> new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(item))));
    }

    @Test void shareSheet() {
        var share = new ShareSheet("Moja d.o.o.", false, "87654321", true,
                List.of(new ShareSheet.SubsequentPayment("87654321", d("2024-05-05"), n("100.5"))),
                List.of(new ShareSheet.Row(Kind.PURCHASE, d("2020-01-01"), "A", n("1000"), null, null, null, null, n("100"), "t"),
                        new ShareSheet.Row(Kind.SALE, d("2025-02-02"), null, null, null, n("25.5"), n("700"), false, n("74.5"), "t")));
        var item = new KdvpItem(SheetType.PLD, null, null, null, null, null, null, null, null, false, null, share, null);
        String xml = new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(item)));
        assertValid(FormType.KDVP, xml);
        assertTrue(xml.contains("<ShareCount>1</ShareCount>") && xml.contains("<F6>25.5000</F6>"));
    }

    @Test void capitalReductionSheet() {
        var cr = new CapitalReductionSheet("US0378331005", "SYM", "Name", "R-1", d("2024-06-06"), List.of(
                new CapitalReductionSheet.Row(Kind.PURCHASE, d("2021-01-01"), "B", n("10"), n("5"), null, null, "t"),
                new CapitalReductionSheet.Row(Kind.SALE, d("2025-01-01"), null, null, null, n("20"), n("10"), "t")));
        var item = new KdvpItem(SheetType.PLVPZOK, null, null, null, null, null, null, null, null, null, null, null, cr);
        String xml = new KdvpXmlWriter().write(new KdvpReport(tp, 2025, true, List.of(item)));
        assertValid(FormType.KDVP, xml);
        assertTrue(xml.contains("<SecurityCapitalReductionCount>1</SecurityCapitalReductionCount>"));
    }

    @Test void headerTaxReliefTaxBaseDecreaseAttachmentAndRemission() {
        var item = KdvpItem.ofSecurity(SheetType.PLVP, sec("US0378331005", null, null, buy("2024-01-02", "3", "10")));
        var report = new KdvpReport(tp, 2025, d("2025-01-01"), d("2025-12-31"), false, "040", "AVSTRIJA",
                "+38640123456", "a@b.si", new Remission("1", "2", "Finanzamt", d("2025-02-01")),
                List.of(item),
                List.of(new TaxRelief("O-1", d("2024-01-01"), d("2010-01-01"), d("2024-01-01"), n("100"), null, n("5.5"), false)),
                List.of(new TaxBaseDecrease("O-2", d("2024-02-02"), "12", "Ljubljana", n("50"))),
                List.of("dokument.pdf"));
        assertValid(FormType.KDVP, new KdvpXmlWriter().write(report));
    }

    @Test void itemRequiresMatchingContent() {
        assertThrows(IllegalArgumentException.class, () -> new KdvpItem(SheetType.PLD, null, null, null, null, null,
                null, null, null, null, sec("US0378331005", null, null), null, null));
    }

    @Test void foreignTaxNeedsCountryCode() {
        assertThrows(IllegalArgumentException.class, () -> new KdvpItem(SheetType.PLVP, null, true, n("1"), null, null,
                null, null, null, null, sec("US0378331005", null, null), null, null));
    }

    @Test void divWithAllBlocks() {
        var div = new Dividend(d("2025-05-15"), "12345678", "ID-1", "Apple Inc.", "Cupertino", "US", "1",
                n("12.34"), n("1.85"), "US", "Treaty SI-US", "t");
        var corp = new CorpData(1, "Moja d.o.o.", d("2025-02-02"), n("100"), n("10"), n("50"), n("5"), n("1000.00"), n("12.5"));
        var detail = new CorpDataDetail(1, d("2020-01-01"), "A", n("100"), n("10"), n("1000"), n("900"), n("50"), n("5"),
                n("450"), n("400"), n("410"));
        var sub = new SubseqSubmission(new SubseqSubmission.Decision("S-1", d("2025-01-01"), d("2025-02-01")),
                new SubseqSubmission.Proposal(true, d("2025-01-02"), d("2025-03-01"), "bolezen"));
        var rep = new DivReport(tp, 2025, true, "SI", "a@b.si", "040123456", false, "opomba",
                List.of(div), List.of(corp), List.of(detail), sub);
        assertValid(FormType.DIV, new DivXmlWriter().write(rep));
    }

    @Test void divWithoutDividendsIsStillValid() {
        assertValid(FormType.DIV, new DivXmlWriter().write(new DivReport(tp, 2025, List.of())));
    }
}
