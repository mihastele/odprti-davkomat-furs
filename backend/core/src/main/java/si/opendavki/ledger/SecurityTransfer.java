package si.opendavki.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Securities moved between brokers (or in from outside the statements). A transfer-in carries the original
 * acquisition date and cost basis when known, which is what makes cross-broker FIFO possible.
 */
public record SecurityTransfer(String id, String broker, String account, LocalDate date, SourceRef source,
                               Instrument instrument, Direction direction, BigDecimal quantity,
                               LocalDate acquisitionDate, BigDecimal costBasisPerUnit, String costCurrency,
                               String counterparty) implements LedgerEntry {

    public enum Direction { IN, OUT }

    public SecurityTransfer {
        LedgerChecks.common(id, broker, account, date, instrument);
        if (direction == null) throw new IllegalArgumentException("direction is required");
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (costBasisPerUnit != null) {
            if (costBasisPerUnit.signum() < 0) throw new IllegalArgumentException("costBasisPerUnit must be >= 0");
            LedgerChecks.currency(costCurrency);
        }
    }

    @Override public int dayOrder() { return direction == Direction.IN ? 1 : 5; }
}
