package si.opendavki.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.model.*;
import si.opendavki.model.KdvpReport;

/**
 * Output of {@link TaxEngine}. {@code kdvp}/{@code div} are null when there is nothing to declare. If {@code complete}
 * is false, at least one instrument or payment was left out because of an ERROR diagnostic: the reports must not be
 * filed as they stand.
 */
public record TaxResult(int year, KdvpReport kdvp, DivReport div, List<Diagnostic> diagnostics,
                        List<LotMatch> lotMatches, List<RateUse> rates, boolean complete, boolean feesIncluded) {

    /** The audit trail as a serialisable document. */
    public record Audit(int year, boolean complete, boolean feesIncluded, List<LotMatch> lotMatches,
                        List<SheetAudit> sheets, List<Dividend> dividends, List<RateUse> rates, List<Diagnostic> diagnostics) {}

    /** One Doh-KDVP sheet exactly as written to the XML: {@code id} is the row ID, {@code source} the statement line behind it. */
    public record SheetAudit(String isin, String symbol, String name, List<SheetRow> rows) {}

    public record SheetRow(int id, String kind, LocalDate date, BigDecimal quantity, BigDecimal unitPriceEur, String acquisitionType,
                           BigDecimal stockAfter, String source) {}

    public Audit audit() {
        return new Audit(year, complete, feesIncluded, lotMatches, sheets(), div == null ? List.of() : div.dividends(), rates, diagnostics);
    }

    private List<SheetAudit> sheets() {
        if (kdvp == null) return List.of();
        List<SheetAudit> out = new ArrayList<>();
        for (KdvpItem item : kdvp.items()) {
            Security s = item.security();
            if (s == null) continue;
            List<SheetRow> rows = new ArrayList<>();
            BigDecimal stock = BigDecimal.ZERO;
            int id = 0;
            for (SecurityEvent e : KdvpXmlWriter.ordered(s.events())) {
                boolean purchase = e.kind() == SecurityEvent.Kind.PURCHASE;
                stock = purchase ? stock.add(e.quantity()) : stock.subtract(e.quantity());
                rows.add(new SheetRow(id++, e.kind().name(), e.date(), e.quantity(), e.unitPrice(), purchase ? e.acquisitionType() : null, stock, e.sourceRef()));
            }
            out.add(new SheetAudit(s.isin(), s.code(), s.name(), List.copyOf(rows)));
        }
        return List.copyOf(out);
    }
}
