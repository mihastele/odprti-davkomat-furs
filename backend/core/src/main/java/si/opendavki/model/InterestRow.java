package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One interest payment row of Doh-Obr. Amounts are EUR.
 *
 * @param payerName     who paid (the broker or bank); address, tax and identification numbers are not in broker statements
 * @param payerCountry  country of the payer, null if the statement does not say (never guessed)
 * @param type          Doh-Obr interest type code ("Šifra vrste obresti"), null if not configured: the schema does not list the codes
 * @param sourceCountry country the interest comes from, null if unknown
 * @param sourceRef     pointer back to the statement line; never exported
 */
public record InterestRow(LocalDate date, String payerName, String payerAddress, String payerCountry, String type,
                          BigDecimal value, BigDecimal foreignTax, String sourceCountry, String sourceRef) {
    public InterestRow {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (value == null || value.signum() < 0) throw new IllegalArgumentException("value must be >= 0");
        if (foreignTax == null) foreignTax = BigDecimal.ZERO;
        if (foreignTax.signum() < 0) throw new IllegalArgumentException("foreignTax must be >= 0");
        if (payerCountry != null && !payerCountry.matches("[A-Z]{2}")) throw new IllegalArgumentException("payerCountry must be ISO 3166 alpha-2: " + payerCountry);
        if (sourceCountry != null && !sourceCountry.matches("[A-Z]{2}")) throw new IllegalArgumentException("sourceCountry must be ISO 3166 alpha-2: " + sourceCountry);
    }
}
