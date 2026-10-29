package si.opendavki.ledger;

import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pairs an outgoing transfer with the incoming transfer of the same instrument and quantity at a different account within
 * a few days: a move between the owner's own accounts. A heuristic (BACKLOG T-04): anything it cannot pair is reported by
 * the callers, never silently assumed to be an own-account move.
 */
public final class TransferPairing {
    public static final int MAX_DAYS_APART = 10;

    private TransferPairing() {}

    /** Ids of all transfers that found a partner. {@code entries} must all be of one instrument. */
    public static Set<String> paired(List<? extends LedgerEntry> entries) {
        Set<String> paired = new HashSet<>();
        for (LedgerEntry e : entries) {
            if (!(e instanceof SecurityTransfer out) || out.direction() != SecurityTransfer.Direction.OUT) continue;
            for (LedgerEntry c : entries) {
                if (c instanceof SecurityTransfer in && in.direction() == SecurityTransfer.Direction.IN
                        && !paired.contains(in.id()) && in.quantity().compareTo(out.quantity()) == 0
                        && !(in.broker().equals(out.broker()) && in.account().equals(out.account()))
                        && Math.abs(ChronoUnit.DAYS.between(in.date(), out.date())) <= MAX_DAYS_APART) {
                    paired.add(in.id());
                    paired.add(out.id());
                    break;
                }
            }
        }
        return paired;
    }
}
