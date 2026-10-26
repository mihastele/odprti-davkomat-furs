package si.opendavki.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A buy or sell. {@code price} is per unit in {@code currency}; {@code fee} is the total commission in
 * {@code feeCurrency} (zero if the broker charged none).
 */
public record Trade(String id, String broker, String account, LocalDate date, SourceRef source, Instrument instrument,
                    Side side, BigDecimal quantity, BigDecimal price, String currency,
                    BigDecimal fee, String feeCurrency) implements LedgerEntry {

    public enum Side { BUY, SELL }

    public Trade {
        LedgerChecks.common(id, broker, account, date, instrument);
        if (side == null) throw new IllegalArgumentException("side is required");
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (price == null || price.signum() < 0) throw new IllegalArgumentException("price must be >= 0");
        LedgerChecks.currency(currency);
        if (fee == null) fee = BigDecimal.ZERO;
        if (fee.signum() < 0) throw new IllegalArgumentException("fee must be >= 0");
        if (feeCurrency == null || feeCurrency.isBlank()) feeCurrency = currency;
        LedgerChecks.currency(feeCurrency);
    }

    @Override public int dayOrder() { return side == Side.BUY ? 2 : 3; }
}
