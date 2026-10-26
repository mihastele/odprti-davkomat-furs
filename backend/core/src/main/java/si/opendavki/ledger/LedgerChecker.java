package si.opendavki.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Consistency checks that do not need tax rules: positions must never go negative at any broker account
 * (a sale without a recorded purchase or transfer-in means history is missing), instruments should carry an ISIN,
 * and unsupported corporate actions are called out.
 */
public final class LedgerChecker {
    private LedgerChecker() {}

    public static List<Diagnostic> check(Ledger ledger) {
        List<Diagnostic> out = new ArrayList<>();
        Map<String, BigDecimal> positions = new HashMap<>();
        Map<String, Boolean> warnedNoIsin = new HashMap<>();

        for (LedgerEntry e : ledger.entries()) {
            Instrument ins = e.instrument();
            if (ins.isin() == null && warnedNoIsin.putIfAbsent(ins.key(), true) == null) {
                out.add(Diagnostic.warning("MISSING_ISIN", "No ISIN for " + ins.symbol()
                        + "; Doh-KDVP needs it and cross-broker matching falls back to the symbol", e.source()));
            }
            String key = e.broker() + "\u0000" + e.account() + "\u0000" + ins.key();
            BigDecimal pos = positions.getOrDefault(key, BigDecimal.ZERO);
            switch (e) {
                case Trade t -> {
                    pos = t.side() == Trade.Side.BUY ? pos.add(t.quantity()) : pos.subtract(t.quantity());
                    if (pos.signum() < 0) {
                        out.add(negative(e, ins, pos));
                        pos = BigDecimal.ZERO; // report each shortfall once, then continue from zero
                    }
                }
                case SecurityTransfer s -> {
                    pos = s.direction() == SecurityTransfer.Direction.IN ? pos.add(s.quantity()) : pos.subtract(s.quantity());
                    if (pos.signum() < 0) {
                        out.add(negative(e, ins, pos));
                        pos = BigDecimal.ZERO;
                    }
                }
                case CorporateAction c -> {
                    if (c.type() == CorporateAction.Type.SPLIT) {
                        pos = pos.multiply(c.newQuantity()).divide(c.oldQuantity(), 12, java.math.RoundingMode.HALF_UP);
                    } else {
                        out.add(Diagnostic.warning("UNSUPPORTED_CORPORATE_ACTION", "Corporate action on " + ins.key()
                                + " is not applied automatically; positions and cost basis may be wrong: " + c.description(),
                                e.source()));
                    }
                }
                case DividendPayment d -> { }
            }
            positions.put(key, pos);
        }
        return out;
    }

    private static Diagnostic negative(LedgerEntry e, Instrument ins, BigDecimal pos) {
        return Diagnostic.error("MISSING_PURCHASE_HISTORY", "Position in " + ins.key() + " at " + e.broker() + "/" + e.account()
                + " would be " + pos.stripTrailingZeros().toPlainString() + " after this entry on " + e.date()
                + ": a purchase or transfer-in is missing", e.source());
    }
}
