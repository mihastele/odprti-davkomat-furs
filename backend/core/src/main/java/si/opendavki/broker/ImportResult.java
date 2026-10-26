package si.opendavki.broker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.ledger.LedgerEntry;

/**
 * Outcome of reading one statement. The builder enforces the project's core import rule: every data row that was
 * read is accounted for as mapped, skipped (with a stated reason) or failed (with an error diagnostic).
 * An adapter that loses a row cannot produce a result at all.
 */
public record ImportResult(List<LedgerEntry> entries, List<Diagnostic> diagnostics, Stats stats) {

    public record Stats(int rowsRead, int rowsMapped, int rowsSkipped, int rowsFailed) {}

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
    }

    public static Builder builder(StatementSource source, String adapterId) {
        return new Builder(source, adapterId);
    }

    public static final class Builder {
        private final StatementSource source;
        private final String adapterId;
        private final List<LedgerEntry> entries = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private final Set<Integer> accounted = new HashSet<>();
        private int mapped, skipped, failed;
        private String sha;

        private Builder(StatementSource source, String adapterId) {
            this.source = source;
            this.adapterId = adapterId;
        }

        public String adapterId() { return adapterId; }

        public si.opendavki.ledger.SourceRef ref(int line) {
            if (sha == null) sha = source.sha256();
            return new si.opendavki.ledger.SourceRef(adapterId, source.name(), sha, line);
        }

        /** The row became one or more ledger entries. */
        public Builder mapped(int line, LedgerEntry... produced) {
            account(line);
            if (produced.length == 0) throw new IllegalArgumentException("mapped() needs at least one entry; use skipped()");
            mapped++;
            entries.addAll(List.of(produced));
            return this;
        }

        /** The row is deliberately not imported (e.g. a comment or a cash movement irrelevant to tax). */
        public Builder skipped(int line, String code, String reason) {
            account(line);
            skipped++;
            diagnostics.add(Diagnostic.info(code, reason, ref(line)));
            return this;
        }

        /** The row could not be understood; this is an ERROR the user must resolve. */
        public Builder failed(int line, String code, String reason) {
            account(line);
            failed++;
            diagnostics.add(Diagnostic.error(code, reason, ref(line)));
            return this;
        }

        public Builder diagnostic(Diagnostic d) {
            diagnostics.add(d);
            return this;
        }

        /** @param rowsRead number of data rows the adapter read; must equal the rows accounted for */
        public ImportResult build(int rowsRead) {
            if (rowsRead != mapped + skipped + failed || rowsRead != accounted.size()) {
                throw new IllegalStateException("Adapter " + adapterId + " lost rows in " + source.name() + ": read "
                        + rowsRead + ", accounted " + (mapped + skipped + failed));
            }
            return new ImportResult(List.copyOf(entries), List.copyOf(diagnostics), new Stats(rowsRead, mapped, skipped, failed));
        }

        private void account(int line) {
            if (!accounted.add(line)) throw new IllegalStateException("row " + line + " accounted twice");
        }
    }
}
