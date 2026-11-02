package si.opendavki.tax;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.model.*;

class DeclarationCheckerTest {
    private static final Taxpayer TP = new Taxpayer("12345678", "FO");

    private static SecurityEvent buy(String date) { return new SecurityEvent(SecurityEvent.Kind.PURCHASE, LocalDate.parse(date), BigDecimal.TEN, BigDecimal.ONE, "B", "p"); }
    private static SecurityEvent sell(String date) { return new SecurityEvent(SecurityEvent.Kind.SALE, LocalDate.parse(date), BigDecimal.ONE, BigDecimal.ONE, null, "s"); }
    private static KdvpItem sheet(String isin, String code, String name, SecurityEvent... ev) {
        return KdvpItem.ofSecurity(SheetType.PLVP, new Security(isin, code, name, false, List.of(ev)));
    }
    private static List<String> codes(List<Diagnostic> d) { return d.stream().map(Diagnostic::code).toList(); }

    @Test void aSensibleSheetHasNoFindings() {
        KdvpReport r = new KdvpReport(TP, 2024, true, List.of(sheet("US0378331005", "AAPL", "Apple", buy("2023-01-01"), sell("2024-03-01"))));
        assertEquals(List.of(), DeclarationChecker.check(r, null));
    }

    @Test void salesOutsideThePeriodAndSheetsWithoutSalesAreCaught() {
        KdvpReport r = new KdvpReport(TP, 2024, true, List.of(sheet("US0378331005", "AAPL", "Apple", buy("2023-01-01"), sell("2023-06-01")),
                sheet("US5949181045", "MSFT", "Microsoft", buy("2023-01-01"))));
        assertEquals(List.of("SHEET_WITHOUT_SALE", "SALE_OUTSIDE_PERIOD", "SHEET_WITHOUT_SALE"), codes(DeclarationChecker.check(r, null)));
    }

    @Test void saleBeforeAnyPurchaseAndPurchaseAfterThePeriodAreErrors() {
        KdvpReport r = new KdvpReport(TP, 2024, true, List.of(sheet("US0378331005", "AAPL", "Apple", buy("2024-05-01"), sell("2024-03-01"), buy("2025-02-01"))));
        List<String> c = codes(DeclarationChecker.check(r, null));
        assertTrue(c.contains("SALE_BEFORE_ANY_PURCHASE"), c.toString());
        assertTrue(c.contains("PURCHASE_AFTER_PERIOD"), c.toString());
    }

    @Test void duplicateSheetsAndOverlongFieldsAreErrors() {
        KdvpReport r = new KdvpReport(TP, 2024, true, List.of(sheet("US0378331005", "VERYLONGCODE", "x".repeat(101), buy("2023-01-01"), sell("2024-03-01")),
                sheet("US0378331005", "AAPL", "Apple", buy("2023-01-01"), sell("2024-03-01"))));
        List<String> c = codes(DeclarationChecker.check(r, null));
        assertEquals(2, c.stream().filter(x -> x.equals("FIELD_TOO_LONG")).count());
        assertEquals(1, c.stream().filter(x -> x.equals("DUPLICATE_SHEET")).count());
    }

    private static Dividend dividend(String date, String gross, String tax) {
        return new Dividend(LocalDate.parse(date), null, "Apple", null, "US", "1", new BigDecimal(gross), new BigDecimal(tax), "US", null, "d");
    }

    @Test void dividendChecks() {
        DivReport ok = new DivReport(TP, 2024, List.of(dividend("2024-05-01", "10", "1.5")));
        assertEquals(List.of(), DeclarationChecker.check(null, ok));
        assertEquals(List.of("DIVIDEND_OUTSIDE_PERIOD"), codes(DeclarationChecker.check(null, new DivReport(TP, 2024, List.of(dividend("2023-12-31", "10", "1"))))));
        assertEquals(List.of("FOREIGN_TAX_EXCEEDS_GROSS"), codes(DeclarationChecker.check(null, new DivReport(TP, 2024, List.of(dividend("2024-05-01", "10", "11"))))));
        // 60% withheld is suspicious, exactly half is not
        assertEquals(List.of("FOREIGN_TAX_HIGH"), codes(DeclarationChecker.check(null, new DivReport(TP, 2024, List.of(dividend("2024-05-01", "10", "6"))))));
        assertEquals(List.of(), DeclarationChecker.check(null, new DivReport(TP, 2024, List.of(dividend("2024-05-01", "10", "5")))));
    }
}
