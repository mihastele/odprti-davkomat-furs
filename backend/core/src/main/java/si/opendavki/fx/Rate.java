package si.opendavki.fx;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The rate used for a conversion, with provenance so it can be shown in the audit report.
 *
 * @param unitsPerEur   foreign units for 1 EUR (1 for EUR itself)
 * @param requestedDate the transaction date
 * @param rateDate      the date the rate was published for (earlier than requested if the day had no rate)
 */
public record Rate(FxSource source, String currency, LocalDate requestedDate, LocalDate rateDate, BigDecimal unitsPerEur) {

    public boolean isFallback() {
        return !rateDate.equals(requestedDate);
    }

    /** Converts to EUR at 12 decimals (HALF_UP); final rounding to cents belongs to the declaration, not here. */
    public BigDecimal toEur(BigDecimal amount) {
        return amount.divide(unitsPerEur, 12, RoundingMode.HALF_UP);
    }
}
