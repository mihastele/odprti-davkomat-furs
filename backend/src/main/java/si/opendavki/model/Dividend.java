package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One dividend payment row of Doh-Div. Amounts are EUR.
 *
 * @param type           Doh-Div dividend type code ("1" = ordinary dividend)
 * @param foreignTax     tax withheld abroad (0 if none)
 * @param reliefStatement optional treaty-relief reference text
 * @param sourceRef      pointer back to the original broker statement line; never exported
 */
public record Dividend(
        LocalDate date,
        String payerTaxNumber,
        String payerIdentificationNumber,
        String payerName,
        String payerAddress,
        String payerCountry,
        String type,
        BigDecimal grossValue,
        BigDecimal foreignTax,
        String sourceCountry,
        String reliefStatement,
        String sourceRef) {

    public Dividend {
        if (date == null) throw new IllegalArgumentException("date is required");
        if (grossValue == null || grossValue.signum() < 0) throw new IllegalArgumentException("grossValue must be >= 0");
        if (foreignTax == null) foreignTax = BigDecimal.ZERO;
        if (foreignTax.signum() < 0) throw new IllegalArgumentException("foreignTax must be >= 0");
        if (type == null || type.isBlank()) type = "1";
        if (payerCountry == null || !payerCountry.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("payerCountry must be ISO 3166 alpha-2, got: " + payerCountry);
        }
        if (sourceCountry == null || sourceCountry.isBlank()) sourceCountry = payerCountry;
    }

    public Dividend(LocalDate date, String payerTaxNumber, String payerName, String payerAddress, String payerCountry,
                    String type, BigDecimal grossValue, BigDecimal foreignTax, String sourceCountry,
                    String reliefStatement, String sourceRef) {
        this(date, payerTaxNumber, null, payerName, payerAddress, payerCountry, type, grossValue, foreignTax,
                sourceCountry, reliefStatement, sourceRef);
    }
}
