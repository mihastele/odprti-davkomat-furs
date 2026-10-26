package si.opendavki.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A corporate action. Only {@link Type#SPLIT} (and reverse splits as new &lt; old) is applied automatically;
 * anything else is carried as {@link Type#UNSUPPORTED} and raises a diagnostic so it is handled by hand
 * rather than silently ignored.
 *
 * @param newQuantity/oldQuantity for a split: holders get {@code new} shares for every {@code old} held
 */
public record CorporateAction(String id, String broker, String account, LocalDate date, SourceRef source,
                              Instrument instrument, Type type, BigDecimal newQuantity, BigDecimal oldQuantity,
                              String description) implements LedgerEntry {

    public enum Type { SPLIT, UNSUPPORTED }

    public CorporateAction {
        LedgerChecks.common(id, broker, account, date, instrument);
        if (type == null) throw new IllegalArgumentException("type is required");
        if (type == Type.SPLIT) {
            if (newQuantity == null || oldQuantity == null || newQuantity.signum() <= 0 || oldQuantity.signum() <= 0) {
                throw new IllegalArgumentException("split needs positive newQuantity and oldQuantity");
            }
        }
    }

    @Override public int dayOrder() { return 0; }
}
