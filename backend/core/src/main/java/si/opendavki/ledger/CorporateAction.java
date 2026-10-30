package si.opendavki.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

/**
 * A corporate action, applied to positions as follows:
 * <ul>
 *   <li>{@link Type#SPLIT}: holders get {@code newQuantity} shares for every {@code oldQuantity} held (reverse splits are
 *       new &lt; old). Same instrument, lots keep their dates; unit costs are divided by the ratio.</li>
 *   <li>{@link Type#CONVERSION}: the instrument is exchanged for {@code target} (ISIN change, share exchange without
 *       cash) at {@code newQuantity} target units per {@code oldQuantity} old units. Lots move to the target keeping their
 *       acquisition dates and total cost, i.e. the exchange is treated as tax-neutral (BACKLOG C-01, unverified).
 *       Always explicit: nothing infers a conversion.</li>
 *   <li>{@link Type#UNSUPPORTED}: anything else (mergers with cash, spin-offs, rights, ...). Raises diagnostics so it is
 *       handled by hand instead of being silently ignored.</li>
 * </ul>
 *
 * @param target only for {@link Type#CONVERSION}: the instrument holders end up with
 */
public record CorporateAction(String id, String broker, String account, LocalDate date, SourceRef source,
                              Instrument instrument, Type type, BigDecimal newQuantity, BigDecimal oldQuantity,
                              String description, Instrument target) implements LedgerEntry {

    public enum Type { SPLIT, CONVERSION, UNSUPPORTED }

    public CorporateAction {
        LedgerChecks.common(id, broker, account, date, instrument);
        if (type == null) throw new IllegalArgumentException("type is required");
        if (type == Type.SPLIT || type == Type.CONVERSION) {
            if (newQuantity == null || oldQuantity == null || newQuantity.signum() <= 0 || oldQuantity.signum() <= 0) {
                throw new IllegalArgumentException(type.name().toLowerCase() + " needs positive newQuantity and oldQuantity");
            }
        }
        if (type == Type.CONVERSION) {
            if (target == null) throw new IllegalArgumentException("conversion needs a target instrument");
            if (target.key().equals(instrument.key())) {
                throw new IllegalArgumentException("conversion target must differ from the converted instrument: " + target.key());
            }
        } else if (target != null) {
            throw new IllegalArgumentException("only a conversion has a target instrument");
        }
    }

    public CorporateAction(String id, String broker, String account, LocalDate date, SourceRef source, Instrument instrument,
                           Type type, BigDecimal newQuantity, BigDecimal oldQuantity, String description) {
        this(id, broker, account, date, source, instrument, type, newQuantity, oldQuantity, description, null);
    }

    @Override public int dayOrder() { return 0; }

    @Override public LedgerEntry mapInstruments(UnaryOperator<Instrument> f) {
        return new CorporateAction(id, broker, account, date, source, f.apply(instrument), type, newQuantity, oldQuantity,
                description, target == null ? null : f.apply(target));
    }
}
