package si.opendavki.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.model.Dividend;

/**
 * Renders the audit trail as Markdown so every figure can be followed back to a statement line: per-instrument summary,
 * each sale with the lots it consumed (and where each lot was bought), dividends, every exchange rate used and every
 * diagnostic. Deterministic: no timestamps, stable ordering.
 *
 * <p>Amounts are shown rounded for reading (EUR cents, unit values at the 8 decimals written to XML); the underlying
 * figures keep 12 decimals, so rounded rows may not add up to the rounded totals to the cent.
 */
public final class AuditReport {
    private AuditReport() {}

    public static String markdown(TaxResult.Audit a) {
        StringBuilder md = new StringBuilder();
        md.append("# Audit report ").append(a.year()).append("\n\n");
        if (a.complete()) {
            md.append("**Status: complete.** No instrument or payment was left out.\n\n");
        } else {
            md.append("**Status: INCOMPLETE.** At least one instrument or payment was left out because of an error (see ")
                    .append("[Diagnostics](#diagnostics)). Do not file the declarations as they stand.\n\n");
        }
        md.append("Fees ").append(a.feesIncluded() ? "are" : "are **not**")
                .append(" included in purchase cost and sale value. Gains below are an audit cross-check: eDavki computes the ")
                .append("declared figures itself from the sheet rows. Rates and rules are listed so each number can be re-derived.\n\n");

        summary(md, a);
        sales(md, a);
        dividends(md, a);
        rates(md, a);
        diagnostics(md, a);
        return md.toString();
    }

    // ---- sections ---------------------------------------------------------------------------------------------

    private static void summary(StringBuilder md, TaxResult.Audit a) {
        md.append("## Summary of capital gains\n\n");
        if (a.lotMatches().isEmpty()) {
            md.append("No sales are included.\n\n");
            return;
        }
        Map<String, List<LotMatch>> byInstrument = new TreeMap<>();
        for (LotMatch m : a.lotMatches()) byInstrument.computeIfAbsent(m.instrument(), k -> new ArrayList<>()).add(m);

        md.append("| Instrument | Symbol | Quantity sold | Proceeds EUR | Cost EUR | Gain / loss EUR |\n|---|---|---:|---:|---:|---:|\n");
        BigDecimal totalProceeds = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
        for (Map.Entry<String, List<LotMatch>> e : byInstrument.entrySet()) {
            BigDecimal qty = BigDecimal.ZERO, proceeds = BigDecimal.ZERO, cost = BigDecimal.ZERO;
            for (LotMatch m : e.getValue()) {
                qty = qty.add(m.quantity());
                proceeds = proceeds.add(m.quantity().multiply(m.unitProceedsEur()));
                cost = cost.add(m.quantity().multiply(m.unitCostEur()));
            }
            totalProceeds = totalProceeds.add(proceeds);
            totalCost = totalCost.add(cost);
            md.append("| ").append(cell(e.getKey())).append(" | ").append(cell(e.getValue().get(0).symbol())).append(" | ")
                    .append(plain(qty)).append(" | ").append(eur(proceeds)).append(" | ").append(eur(cost)).append(" | ")
                    .append(eur(proceeds.subtract(cost))).append(" |\n");
        }
        md.append("| **Total** | | | ").append(eur(totalProceeds)).append(" | ").append(eur(totalCost)).append(" | ")
                .append(eur(totalProceeds.subtract(totalCost))).append(" |\n\n");
    }

    private static void sales(StringBuilder md, TaxResult.Audit a) {
        if (a.lotMatches().isEmpty()) return;
        md.append("## Sales and the lots they consumed (FIFO)\n\n");
        Map<String, List<LotMatch>> bySale = new LinkedHashMap<>();
        for (LotMatch m : a.lotMatches()) bySale.computeIfAbsent(m.instrument() + "\u0000" + m.saleEntryId(), k -> new ArrayList<>()).add(m);
        for (List<LotMatch> slices : bySale.values()) {
            LotMatch first = slices.get(0);
            BigDecimal qty = slices.stream().map(LotMatch::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            md.append("### ").append(first.saleDate()).append(" sold ").append(plain(qty)).append(' ')
                    .append(first.symbol() != null ? first.symbol() : first.instrument()).append(" (").append(first.instrument())
                    .append(")\n\nSale line: `").append(first.saleSource()).append("` at ")
                    .append(unit(first.unitProceedsEur())).append(" EUR per unit after conversion").append(a.feesIncluded() ? " and fees" : "")
                    .append(".\n\n| Lot bought | Lot line | Quantity | Unit cost EUR | Unit proceeds EUR | Gain / loss EUR |\n|---|---|---:|---:|---:|---:|\n");
            for (LotMatch m : slices) {
                md.append("| ").append(m.lotDate()).append(" | `").append(m.lotSource()).append("` | ").append(plain(m.quantity()))
                        .append(" | ").append(unit(m.unitCostEur())).append(" | ").append(unit(m.unitProceedsEur())).append(" | ")
                        .append(m.gainEur().setScale(4, RoundingMode.HALF_UP).toPlainString()).append(" |\n");
            }
            md.append('\n');
        }
    }

    private static void dividends(StringBuilder md, TaxResult.Audit a) {
        md.append("## Dividends (Doh-Div)\n\n");
        if (a.dividends().isEmpty()) {
            md.append("No dividends are included.\n\n");
            return;
        }
        md.append("Dividends are reported separately and never netted against capital gains.\n\n")
                .append("| Date | Payer | Country | Gross EUR | Foreign tax EUR | Source line |\n|---|---|---|---:|---:|---|\n");
        BigDecimal gross = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (Dividend d : a.dividends()) {
            gross = gross.add(d.grossValue());
            tax = tax.add(d.foreignTax());
            md.append("| ").append(d.date()).append(" | ").append(cell(d.payerName())).append(" | ").append(d.payerCountry())
                    .append(" | ").append(eur(d.grossValue())).append(" | ").append(eur(d.foreignTax())).append(" | `")
                    .append(d.sourceRef()).append("` |\n");
        }
        md.append("| **Total** | | | ").append(eur(gross)).append(" | ").append(eur(tax)).append(" | |\n\n");
    }

    private static void rates(StringBuilder md, TaxResult.Audit a) {
        md.append("## Exchange rates used\n\n");
        if (a.rates().isEmpty()) {
            md.append("No foreign-currency amounts were converted.\n\n");
            return;
        }
        md.append("Rate = foreign units per 1 EUR. A rate date different from the transaction date means the day had no rate and ")
                .append("the latest earlier one was used.\n\n")
                .append("| Source line | Amount | Currency | Transaction date | Rate date | Rate | Source |\n|---|---|---|---|---|---:|---|\n");
        for (RateUse r : a.rates()) {
            md.append("| `").append(r.source()).append("` | ").append(cell(r.what())).append(" | ").append(r.currency()).append(" | ")
                    .append(r.requestedDate()).append(" | ").append(r.rateDate()).append(r.rateDate().equals(r.requestedDate()) ? "" : " (earlier day)")
                    .append(" | ").append(r.unitsPerEur().toPlainString()).append(" | ").append(r.rateSource()).append(" |\n");
        }
        md.append('\n');
    }

    private static void diagnostics(StringBuilder md, TaxResult.Audit a) {
        md.append("## Diagnostics\n\n");
        long info = a.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.INFO).count();
        boolean any = false;
        for (Diagnostic.Severity s : List.of(Diagnostic.Severity.ERROR, Diagnostic.Severity.WARNING)) {
            List<Diagnostic> list = a.diagnostics().stream().filter(d -> d.severity() == s).toList();
            if (list.isEmpty()) continue;
            any = true;
            md.append("**").append(s == Diagnostic.Severity.ERROR ? "Errors" : "Warnings").append(" (").append(list.size()).append(")**\n\n");
            for (Diagnostic d : list) {
                md.append("- `").append(d.code()).append('`');
                if (d.source() != null) md.append(" at `").append(d.source()).append('`');
                md.append(": ").append(d.message().replace("\n", " ")).append('\n');
            }
            md.append('\n');
        }
        if (!any) md.append("No errors or warnings.\n\n");
        if (info > 0) md.append(info).append(" informational note(s) (e.g. paired transfers, rate fallbacks) are in the audit JSON.\n");
    }

    // ---- formatting -------------------------------------------------------------------------------------------

    private static String eur(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP).toPlainString(); }

    private static String unit(BigDecimal v) { return v.setScale(8, RoundingMode.HALF_UP).toPlainString(); }

    private static String plain(BigDecimal v) { return v.stripTrailingZeros().toPlainString(); }

    private static String cell(String s) { return s == null ? "" : s.replace("|", "\\|").replace("\n", " "); }
}
