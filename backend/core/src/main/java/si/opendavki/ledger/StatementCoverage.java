package si.opendavki.ledger;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The period each imported statement covers, so a hole between two exports of the same broker is noticed. Brokers such as
 * Trading 212 limit an export to a year: forgetting one makes later sales fail with MISSING_PURCHASE_HISTORY.
 */
public final class StatementCoverage {
    /** A gap longer than this between consecutive statements of one adapter is reported. Quiet periods can be real. */
    public static final int GAP_DAYS = 31;

    private StatementCoverage() {}

    public record Coverage(String file, String adapter, LocalDate first, LocalDate last) {
        /** Null when the statement produced no dated entries. */
        public static Coverage of(String file, String adapter, List<? extends LedgerEntry> entries) {
            if (entries.isEmpty()) return null;
            LocalDate min = entries.stream().map(LedgerEntry::date).min(Comparator.naturalOrder()).orElseThrow();
            LocalDate max = entries.stream().map(LedgerEntry::date).max(Comparator.naturalOrder()).orElseThrow();
            return new Coverage(file, adapter, min, max);
        }
    }

    public static List<Diagnostic> gaps(List<Coverage> coverages) {
        List<Diagnostic> out = new ArrayList<>();
        List<Coverage> sorted = coverages.stream().sorted(Comparator.comparing(Coverage::adapter).thenComparing(Coverage::first)).toList();
        Coverage previous = null;
        for (Coverage c : sorted) {
            if (previous != null && previous.adapter().equals(c.adapter())) {
                long gap = ChronoUnit.DAYS.between(previous.last(), c.first());
                if (gap > GAP_DAYS) {
                    out.add(Diagnostic.warning("STATEMENT_GAP", c.file() + " (" + c.adapter() + ") starts " + c.first() + " but "
                            + previous.file() + " ends " + previous.last() + ": nothing covers the " + gap + " days in between; sales of lots "
                            + "bought then will fail with MISSING_PURCHASE_HISTORY", null));
                }
            }
            if (previous == null || !previous.adapter().equals(c.adapter()) || c.last().isAfter(previous.last())) previous = c;
        }
        return out;
    }
}
