package si.opendavki.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

/**
 * Interest credited on cash or similar (declared on Doh-Obr). Not tied to an instrument, so {@link #instrument()} is null.
 * Amounts are in {@code currency}; interest charged to the account (debit interest) is not income and is never an entry.
 *
 * @param payer         who paid, as far as the statement says (usually the broker); null if unknown
 * @param sourceCountry country of the payer if the statement states it; never inferred
 * @param withholdingTax tax withheld at source, 0 if none
 */
public record InterestPayment(String id, String broker, String account, LocalDate date, SourceRef source, BigDecimal gross,
                              BigDecimal withholdingTax, String currency, String payer, String sourceCountry,
                              String description) implements LedgerEntry {

    public InterestPayment {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (broker == null || broker.isBlank()) throw new IllegalArgumentException("broker is required");
        if (account == null || account.isBlank()) throw new IllegalArgumentException("account is required");
        if (date == null) throw new IllegalArgumentException("date is required");
        if (gross == null || gross.signum() < 0) throw new IllegalArgumentException("gross must be >= 0");
        if (withholdingTax == null) withholdingTax = BigDecimal.ZERO;
        if (withholdingTax.signum() < 0) throw new IllegalArgumentException("withholdingTax must be >= 0");
        LedgerChecks.currency(currency);
        if (sourceCountry != null && !sourceCountry.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("sourceCountry must be ISO 3166 alpha-2: " + sourceCountry);
        }
    }

    @Override public Instrument instrument() { return null; }

    @Override public int dayOrder() { return 4; }

    @Override public LedgerEntry mapInstruments(UnaryOperator<Instrument> f) { return this; }
}
