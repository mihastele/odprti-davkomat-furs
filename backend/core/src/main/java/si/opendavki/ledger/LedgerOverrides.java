package si.opendavki.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import si.opendavki.broker.Csv;

/**
 * Lets the user drop individual ledger entries, for example an UNSUPPORTED corporate action once the real event has been
 * entered by hand, or a duplicate the importers could not recognise. Every ignored entry is reported as a WARNING with the
 * stated reason, so it appears in the audit trail: ignoring is a decision that must stay visible.
 *
 * <p>File format, CSV with header (comment rows start with {@code #}):
 * <pre>
 * id,broker,reason
 * t212-3f2a9c1d44aa1b02,trading212,Stock split entered by hand in corrections.csv
 * </pre>
 * {@code id} is the ledger entry id (see the ledger JSON); {@code broker} is optional but required when several brokers
 * use the same id; {@code reason} is mandatory. An id that matches nothing is a WARNING (a stale override may hide a
 * different problem); one that matches several entries without a broker is an ERROR and is not applied.
 */
public final class LedgerOverrides {
    private record Row(int line, String id, String broker, String reason) {}

    private final String file;
    private final List<Row> rows;

    private LedgerOverrides(String file, List<Row> rows) {
        this.file = file;
        this.rows = rows;
    }

    /** {@code OPENDAVKI_OVERRIDES_FILE}: the overrides file if configured. */
    public static Optional<LedgerOverrides> fromEnvironment(Map<String, String> env) throws IOException {
        String path = env.getOrDefault("OPENDAVKI_OVERRIDES_FILE", "").trim();
        if (path.isEmpty()) return Optional.empty();
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("OPENDAVKI_OVERRIDES_FILE does not exist: " + path);
        return Optional.of(parse(Files.readString(p, StandardCharsets.UTF_8), p.getFileName().toString()));
    }

    public static LedgerOverrides parse(String csv, String fileName) {
        List<Csv.Row> all = Csv.parse(csv, ',').stream().filter(r -> !r.cells().get(0).trim().startsWith("#")).toList();
        if (all.isEmpty()) return new LedgerOverrides(fileName, List.of());
        List<String> header = all.get(0).cells().stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        int iId = header.indexOf("id"), iBroker = header.indexOf("broker"), iReason = header.indexOf("reason"), iAction = header.indexOf("action");
        if (iId < 0 || iReason < 0) throw new IllegalArgumentException(fileName + ": header must contain 'id' and 'reason'");
        List<Row> rows = new ArrayList<>();
        for (Csv.Row r : all.subList(1, all.size())) {
            String where = fileName + ":" + r.line();
            if (r.cells().size() != header.size()) throw new IllegalArgumentException(where + ": expected " + header.size() + " columns");
            if (iAction >= 0 && !r.cells().get(iAction).trim().isEmpty() && !r.cells().get(iAction).trim().equalsIgnoreCase("IGNORE")) {
                throw new IllegalArgumentException(where + ": only the action IGNORE is supported");
            }
            String id = r.cells().get(iId).trim(), reason = r.cells().get(iReason).trim();
            if (id.isEmpty()) throw new IllegalArgumentException(where + ": 'id' is empty");
            if (reason.isEmpty()) throw new IllegalArgumentException(where + ": 'reason' is required; say why this entry is ignored");
            String broker = iBroker < 0 ? "" : r.cells().get(iBroker).trim();
            rows.add(new Row(r.line(), id, broker.isEmpty() ? null : broker, reason));
        }
        return new LedgerOverrides(fileName, List.copyOf(rows));
    }

    public record Result(Ledger ledger, List<Diagnostic> diagnostics) {}

    public Result apply(Ledger ledger) {
        List<Diagnostic> diags = new ArrayList<>();
        Set<LedgerEntry> removed = new HashSet<>();
        for (Row r : rows) {
            List<LedgerEntry> matches = ledger.entries().stream()
                    .filter(e -> e.id().equals(r.id()) && (r.broker() == null || e.broker().equals(r.broker()))).toList();
            String where = file + ":" + r.line();
            if (matches.isEmpty()) {
                diags.add(Diagnostic.warning("OVERRIDE_UNUSED", where + ": no ledger entry has id '" + r.id() + "'"
                        + (r.broker() == null ? "" : " at broker '" + r.broker() + "'") + "; remove the row if the entry is gone", null));
            } else if (matches.stream().map(LedgerEntry::broker).distinct().count() > 1) {
                diags.add(Diagnostic.error("OVERRIDE_AMBIGUOUS", where + ": id '" + r.id() + "' exists at several brokers "
                        + matches.stream().map(LedgerEntry::broker).distinct().toList() + "; add a 'broker' column value; nothing was ignored", null));
            } else {
                for (LedgerEntry e : matches) {
                    removed.add(e);
                    diags.add(Diagnostic.warning("ENTRY_IGNORED", e.getClass().getSimpleName() + " " + e.id() + " of " + e.subject()
                            + " on " + e.date() + " is ignored (" + where + "): " + r.reason(), e.source()));
                }
            }
        }
        List<LedgerEntry> kept = ledger.entries().stream().filter(e -> !removed.contains(e)).toList();
        return new Result(Ledger.of(kept), diags);
    }
}
