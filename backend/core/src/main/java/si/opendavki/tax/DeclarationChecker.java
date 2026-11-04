package si.opendavki.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.model.*;

/**
 * Semantic checks of a finished report that the XSD cannot express: the sheets must make sense for the period, no instrument
 * may appear twice, and field lengths are checked here first so the message can name the instrument. Findings are
 * diagnostics; the engine adds them to its result.
 */
public final class DeclarationChecker {
    private DeclarationChecker() {}

    public static List<Diagnostic> check(KdvpReport kdvp, DivReport div) {
        return check(kdvp, div, null);
    }

    public static List<Diagnostic> check(KdvpReport kdvp, DivReport div, ObrReport obr) {
        List<Diagnostic> out = new ArrayList<>();
        if (kdvp != null) checkKdvp(kdvp, out);
        if (div != null) checkDiv(div, out);
        if (obr != null) checkObr(obr, out);
        return out;
    }

    private static void checkObr(ObrReport r, List<Diagnostic> out) {
        LocalDate start = LocalDate.of(r.year(), 1, 1), end = LocalDate.of(r.year(), 12, 31);
        for (InterestRow i : r.interest()) {
            if (!inPeriod(i.date(), start, end)) {
                out.add(Diagnostic.error("INTEREST_OUTSIDE_PERIOD", "Interest of " + i.payerName() + " on " + i.date() + " is outside " + r.year() + " [" + i.sourceRef() + "]", null));
            }
            if (i.foreignTax().compareTo(i.value()) > 0) {
                out.add(Diagnostic.warning("FOREIGN_TAX_EXCEEDS_GROSS", "Foreign tax " + i.foreignTax().toPlainString() + " EUR is larger than the interest "
                        + i.value().toPlainString() + " EUR on " + i.date() + " [" + i.sourceRef() + "]", null));
            }
        }
    }

    private static void checkKdvp(KdvpReport r, List<Diagnostic> out) {
        Set<String> seen = new HashSet<>();
        for (KdvpItem item : r.items()) {
            if (item.security() == null) continue;
            Security s = item.security();
            String who = s.isin() + (s.code() != null ? " (" + s.code() + ")" : "");
            if (!seen.add(s.isin())) {
                out.add(Diagnostic.error("DUPLICATE_SHEET", "Two Doh-KDVP sheets for " + who + "; eDavki expects one sheet per security", null));
            }
            if (s.code() != null && s.code().length() > 10) {
                out.add(Diagnostic.error("FIELD_TOO_LONG", "Code '" + s.code() + "' of " + who + " is longer than 10 characters", null));
            }
            if (s.name() != null && s.name().length() > 100) {
                out.add(Diagnostic.error("FIELD_TOO_LONG", "Name of " + who + " is longer than 100 characters", null));
            }
            List<SecurityEvent> sales = s.events().stream().filter(e -> e.kind() == SecurityEvent.Kind.SALE).toList();
            List<SecurityEvent> purchases = s.events().stream().filter(e -> e.kind() == SecurityEvent.Kind.PURCHASE).toList();
            if (sales.stream().noneMatch(e -> inPeriod(e.date(), r.periodStart(), r.periodEnd()))) {
                out.add(Diagnostic.warning("SHEET_WITHOUT_SALE", "Sheet of " + who + " has no sale in " + r.periodStart() + " to "
                        + r.periodEnd() + "; it does not need to be declared", null));
            }
            for (SecurityEvent e : sales) {
                if (!inPeriod(e.date(), r.periodStart(), r.periodEnd())) {
                    out.add(Diagnostic.error("SALE_OUTSIDE_PERIOD", "Sale of " + who + " on " + e.date() + " is outside the declaration period ["
                            + e.sourceRef() + "]", null));
                }
                if (purchases.stream().noneMatch(p -> !p.date().isAfter(e.date()))) {
                    out.add(Diagnostic.error("SALE_BEFORE_ANY_PURCHASE", "Sale of " + who + " on " + e.date() + " precedes every purchase on the sheet ["
                            + e.sourceRef() + "]", null));
                }
            }
            for (SecurityEvent e : purchases) {
                if (e.date().isAfter(r.periodEnd())) {
                    out.add(Diagnostic.error("PURCHASE_AFTER_PERIOD", "Purchase of " + who + " on " + e.date() + " is after the declaration period ["
                            + e.sourceRef() + "]", null));
                }
            }
        }
    }

    private static void checkDiv(DivReport r, List<Diagnostic> out) {
        LocalDate start = LocalDate.of(r.year(), 1, 1), end = LocalDate.of(r.year(), 12, 31);
        for (Dividend d : r.dividends()) {
            if (!inPeriod(d.date(), start, end)) {
                out.add(Diagnostic.error("DIVIDEND_OUTSIDE_PERIOD", "Dividend of " + d.payerName() + " on " + d.date() + " is outside " + r.year()
                        + " [" + d.sourceRef() + "]", null));
            }
            if (d.foreignTax().compareTo(d.grossValue()) > 0) {
                out.add(Diagnostic.warning("FOREIGN_TAX_EXCEEDS_GROSS", "Foreign tax " + d.foreignTax().toPlainString() + " EUR is larger than the gross dividend "
                        + d.grossValue().toPlainString() + " EUR of " + d.payerName() + " on " + d.date() + " [" + d.sourceRef() + "]", null));
            } else if (d.grossValue().signum() > 0 && d.foreignTax().multiply(new BigDecimal("100")).compareTo(d.grossValue().multiply(new BigDecimal("50"))) > 0) {
                out.add(Diagnostic.warning("FOREIGN_TAX_HIGH", "Foreign tax is more than half of the gross dividend of " + d.payerName() + " on " + d.date()
                        + " [" + d.sourceRef() + "]; check the statement", null));
            }
        }
    }

    private static boolean inPeriod(LocalDate d, LocalDate from, LocalDate to) {
        return !d.isBefore(from) && !d.isAfter(to);
    }
}
