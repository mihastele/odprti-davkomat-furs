package si.opendavki.tax;

import java.util.List;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.model.DivReport;
import si.opendavki.model.Dividend;
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
                        List<Dividend> dividends, List<RateUse> rates, List<Diagnostic> diagnostics) {}

    public Audit audit() {
        return new Audit(year, complete, feesIncluded, lotMatches, div == null ? List.of() : div.dividends(), rates, diagnostics);
    }
}
