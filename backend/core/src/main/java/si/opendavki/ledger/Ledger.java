package si.opendavki.ledger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The canonical ledger: every entry from every broker in deterministic chronological order.
 * Merging detects duplicates (overlapping statements) instead of double-counting them.
 */
public record Ledger(int formatVersion, List<LedgerEntry> entries) {
    public static final int FORMAT_VERSION = 1;

    private static final Comparator<LedgerEntry> ORDER = Comparator
            .comparing(LedgerEntry::date)
            .thenComparingInt(LedgerEntry::dayOrder)
            .thenComparing(LedgerEntry::broker)
            .thenComparing(LedgerEntry::account)
            .thenComparing(LedgerEntry::id);

    public Ledger {
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported ledger formatVersion " + formatVersion);
        }
        List<LedgerEntry> sorted = new ArrayList<>(entries == null ? List.of() : entries);
        sorted.sort(ORDER);
        entries = List.copyOf(sorted);
    }

    public static Ledger of(List<LedgerEntry> entries) {
        return new Ledger(FORMAT_VERSION, entries);
    }

    public record MergeResult(Ledger ledger, List<Diagnostic> diagnostics) {}

    /**
     * Combines entries. Entries sharing (broker, account, id) are one transaction: if they are identical apart from
     * their source the repeat is dropped with a WARNING (DUPLICATE_ENTRY); if they differ both are kept and an
     * ERROR (CONFLICTING_DUPLICATE) is raised, because one of the statements is wrong.
     */
    public static MergeResult merge(List<? extends LedgerEntry> all) {
        Map<String, LedgerEntry> seen = new LinkedHashMap<>();
        List<LedgerEntry> kept = new ArrayList<>();
        List<Diagnostic> diags = new ArrayList<>();
        for (LedgerEntry e : all) {
            String key = e.broker() + "\u0000" + e.account() + "\u0000" + e.id();
            LedgerEntry first = seen.putIfAbsent(key, e);
            if (first == null) {
                kept.add(e);
            } else if (sameApartFromSource(first, e)) {
                diags.add(Diagnostic.warning("DUPLICATE_ENTRY", "Transaction " + e.id() + " at " + e.broker()
                        + " already imported from " + first.source() + "; ignoring repeat", e.source()));
            } else {
                kept.add(e);
                diags.add(Diagnostic.error("CONFLICTING_DUPLICATE", "Transaction " + e.id() + " at " + e.broker()
                        + " differs from the one imported from " + first.source(), e.source()));
            }
        }
        return new MergeResult(Ledger.of(kept), diags);
    }

    private static boolean sameApartFromSource(LedgerEntry a, LedgerEntry b) {
        return a.getClass() == b.getClass() && withoutSource(a).equals(withoutSource(b));
    }

    private static LedgerEntry withoutSource(LedgerEntry e) {
        return switch (e) {
            case Trade t -> new Trade(t.id(), t.broker(), t.account(), t.date(), null, t.instrument(), t.side(),
                    t.quantity(), t.price(), t.currency(), t.fee(), t.feeCurrency());
            case DividendPayment d -> new DividendPayment(d.id(), d.broker(), d.account(), d.date(), null, d.instrument(),
                    d.gross(), d.withholdingTax(), d.currency(), d.sourceCountry());
            case CorporateAction c -> new CorporateAction(c.id(), c.broker(), c.account(), c.date(), null, c.instrument(),
                    c.type(), c.newQuantity(), c.oldQuantity(), c.description(), c.target());
            case SecurityTransfer s -> new SecurityTransfer(s.id(), s.broker(), s.account(), s.date(), null, s.instrument(),
                    s.direction(), s.quantity(), s.acquisitionDate(), s.costBasisPerUnit(), s.costCurrency(), s.counterparty());
        };
    }
}
