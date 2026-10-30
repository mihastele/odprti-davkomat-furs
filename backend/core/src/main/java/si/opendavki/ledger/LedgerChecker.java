package si.opendavki.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Consistency checks that do not need tax rules:
 * <ul>
 *   <li>positions must never go negative at any broker account (a sale without a recorded purchase or transfer-in means
 *       history is missing);</li>
 *   <li>instruments should carry an ISIN; unsupported corporate actions are called out;</li>
 *   <li>transfers should be pairable between own accounts;</li>
 *   <li>trade prices far from the instrument's usual (split-adjusted) price usually mean a missing split or a unit error;</li>
 *   <li>quantities need no more decimals than the declaration can carry.</li>
 * </ul>
 * All findings are diagnostics; none changes the ledger.
 */
public final class LedgerChecker {
    /** A trade is suspicious when its adjusted price differs from the median by more than this factor (BACKLOG L-12). */
    static final BigDecimal PRICE_FACTOR = new BigDecimal("4");
    /** Fewer trades than this give no meaningful median. */
    static final int MIN_TRADES_FOR_PRICE_CHECK = 3;
    /** Decimals allowed by the Doh-KDVP quantity fields. */
    static final int MAX_QUANTITY_DECIMALS = 8;

    private LedgerChecker() {}

    public static List<Diagnostic> check(Ledger ledger) {
        List<Diagnostic> out = new ArrayList<>();
        positions(ledger, out);
        transfers(ledger, out);
        prices(ledger, out);
        quantityPrecision(ledger, out);
        return out;
    }

    // ---- positions, ISIN, corporate actions -------------------------------------------------------------------

    private static void positions(Ledger ledger, List<Diagnostic> out) {
        Map<String, BigDecimal> positions = new HashMap<>();
        Map<String, Boolean> warnedNoIsin = new HashMap<>();

        for (LedgerEntry e : ledger.entries()) {
            Instrument ins = e.instrument();
            if (ins.isin() == null && warnedNoIsin.putIfAbsent(ins.key(), true) == null) {
                out.add(Diagnostic.warning("MISSING_ISIN", "No ISIN for " + ins.symbol()
                        + "; Doh-KDVP needs it and cross-broker matching falls back to the symbol", e.source()));
            }
            String key = e.broker() + "\u0000" + e.account() + "\u0000" + ins.key();
            if (e instanceof CorporateAction c && c.type() == CorporateAction.Type.CONVERSION) {
                convert(c, key, positions, warnedNoIsin, out);
                continue;
            }
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
                        pos = pos.multiply(c.newQuantity()).divide(c.oldQuantity(), 12, RoundingMode.HALF_UP);
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
    }

    /** The position of the old instrument at this account moves, scaled by the ratio, to the target instrument. */
    private static void convert(CorporateAction c, String oldKey, Map<String, BigDecimal> positions,
                                Map<String, Boolean> warnedNoIsin, List<Diagnostic> out) {
        if (c.target().isin() == null && warnedNoIsin.putIfAbsent(c.target().key(), true) == null) {
            out.add(Diagnostic.warning("MISSING_ISIN", "No ISIN for conversion target " + c.target().symbol()
                    + "; Doh-KDVP needs it and cross-broker matching falls back to the symbol", c.source()));
        }
        BigDecimal moved = positions.remove(oldKey);
        if (moved == null || moved.signum() == 0) {
            out.add(Diagnostic.warning("CONVERSION_WITHOUT_POSITION", "Conversion of " + c.instrument().key() + " into " + c.target().key()
                    + " on " + c.date() + " found no position at " + c.broker() + "/" + c.account(), c.source()));
            return;
        }
        String newKey = c.broker() + "\u0000" + c.account() + "\u0000" + c.target().key();
        BigDecimal converted = moved.multiply(c.newQuantity()).divide(c.oldQuantity(), 12, RoundingMode.HALF_UP);
        positions.merge(newKey, converted, BigDecimal::add);
    }

    private static Diagnostic negative(LedgerEntry e, Instrument ins, BigDecimal pos) {
        return Diagnostic.error("MISSING_PURCHASE_HISTORY", "Position in " + ins.key() + " at " + e.broker() + "/" + e.account()
                + " would be " + pos.stripTrailingZeros().toPlainString() + " after this entry on " + e.date()
                + ": a purchase or transfer-in is missing", e.source());
    }

    // ---- transfers --------------------------------------------------------------------------------------------

    private static void transfers(Ledger ledger, List<Diagnostic> out) {
        Map<String, List<LedgerEntry>> byInstrument = new LinkedHashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            if (e instanceof SecurityTransfer) byInstrument.computeIfAbsent(e.instrument().key(), k -> new ArrayList<>()).add(e);
        }
        for (List<LedgerEntry> group : byInstrument.values()) {
            Set<String> paired = TransferPairing.paired(group);
            for (LedgerEntry e : group) {
                SecurityTransfer s = (SecurityTransfer) e;
                if (paired.contains(s.id())) continue;
                out.add(Diagnostic.warning("TRANSFER_UNPAIRED", (s.direction() == SecurityTransfer.Direction.IN ? "Incoming" : "Outgoing")
                        + " transfer of " + s.quantity().stripTrailingZeros().toPlainString() + " " + s.instrument().key()
                        + " has no counterpart of the same quantity at another account within " + TransferPairing.MAX_DAYS_APART
                        + " days; import the other broker's statement or supply acquisition date and cost basis", s.source()));
            }
        }
    }

    // ---- prices -----------------------------------------------------------------------------------------------

    private record PricedTrade(Trade trade, BigDecimal adjusted) {}

    /**
     * Compares each trade price with the median price of the same instrument and currency, after undoing the splits
     * recorded in the ledger. A large deviation means a split is missing from the ledger or a price/quantity unit is wrong.
     */
    private static void prices(Ledger ledger, List<Diagnostic> out) {
        Map<String, List<CorporateAction>> splits = new HashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            if (e instanceof CorporateAction c && c.type() == CorporateAction.Type.SPLIT) {
                splits.computeIfAbsent(c.instrument().key(), k -> new ArrayList<>()).add(c);
            }
        }
        Map<String, List<PricedTrade>> groups = new LinkedHashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            if (!(e instanceof Trade t) || t.price().signum() == 0) continue;
            groups.computeIfAbsent(t.instrument().key() + "|" + t.currency(), k -> new ArrayList<>())
                    .add(new PricedTrade(t, adjustedPrice(t, splits.getOrDefault(t.instrument().key(), List.of()))));
        }
        for (List<PricedTrade> group : groups.values()) {
            if (group.size() < MIN_TRADES_FOR_PRICE_CHECK) continue;
            BigDecimal median = median(group.stream().map(PricedTrade::adjusted).sorted().toList());
            for (PricedTrade p : group) {
                BigDecimal ratio = p.adjusted().divide(median, 6, RoundingMode.HALF_UP);
                if (ratio.compareTo(PRICE_FACTOR) > 0 || ratio.compareTo(BigDecimal.ONE.divide(PRICE_FACTOR, 6, RoundingMode.HALF_UP)) < 0) {
                    Trade t = p.trade();
                    out.add(Diagnostic.warning("SUSPICIOUS_PRICE", "Price " + t.price().toPlainString() + " " + t.currency() + " of "
                            + t.instrument().key() + " on " + t.date() + " is " + ratio.stripTrailingZeros().toPlainString()
                            + " times the usual split-adjusted price (median " + median.setScale(4, RoundingMode.HALF_UP).toPlainString()
                            + "); a split may be missing or the price/quantity unit is wrong", t.source()));
                }
            }
        }
    }

    /** Price in the units valid after all recorded splits (splits are effective at the start of their day). */
    private static BigDecimal adjustedPrice(Trade t, List<CorporateAction> splits) {
        BigDecimal num = BigDecimal.ONE, den = BigDecimal.ONE;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (CorporateAction s : splits) {
            String key = s.date() + "|" + s.newQuantity().stripTrailingZeros() + "|" + s.oldQuantity().stripTrailingZeros();
            if (s.date().isAfter(t.date()) && seen.add(key)) { num = num.multiply(s.newQuantity()); den = den.multiply(s.oldQuantity()); }
        }
        return t.price().multiply(den).divide(num, 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal median(List<BigDecimal> sorted) {
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
    }

    // ---- quantity precision -----------------------------------------------------------------------------------

    private static void quantityPrecision(Ledger ledger, List<Diagnostic> out) {
        for (LedgerEntry e : ledger.entries()) {
            BigDecimal q = e instanceof Trade t ? t.quantity() : e instanceof SecurityTransfer s ? s.quantity() : null;
            if (q != null && q.stripTrailingZeros().scale() > MAX_QUANTITY_DECIMALS) {
                out.add(Diagnostic.warning("QUANTITY_PRECISION", "Quantity " + q.toPlainString() + " of " + e.instrument().key()
                        + " has more than " + MAX_QUANTITY_DECIMALS + " decimals; the declaration rounds it, so totals may differ slightly",
                        e.source()));
            }
        }
    }
}
