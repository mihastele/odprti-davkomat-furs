package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One purchase or sale row of a Doh-KDVP securities inventory sheet.
 *
 * @param acquisitionType Doh-KDVP code for how a purchase was acquired (e.g. "B" = purchase); ignored for sales
 * @param unitPrice       price per unit in EUR
 * @param sourceRef       pointer back to the original broker statement line; never exported to XML, kept for audit
 */
public record SecurityEvent(
        Kind kind,
        LocalDate date,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String acquisitionType,
        String sourceRef) {

    public enum Kind { PURCHASE, SALE }

    public SecurityEvent {
        if (kind == null || date == null) throw new IllegalArgumentException("kind and date are required");
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (unitPrice == null || unitPrice.signum() < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
        if (kind == Kind.PURCHASE && (acquisitionType == null || acquisitionType.isBlank())) {
            acquisitionType = "B";
        }
    }
}
