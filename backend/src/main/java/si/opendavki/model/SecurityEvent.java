package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One purchase or sale row of a securities inventory sheet. Amounts are EUR.
 *
 * @param acquisitionType Doh-KDVP code for how a purchase was acquired (e.g. "B" = purchase); ignored for sales
 * @param unitPrice       price per unit
 * @param inheritanceTax  purchase only: inheritance/gift tax paid (F5), optional
 * @param reducedUnitPrice purchase only: unit cost reduced by a capital reduction with unchanged quantity (F11), optional
 * @param art97Rule       sale only: rule of art. 97(2) in connection with (5) ZDoh-2 (F10), optional
 * @param sourceRef       pointer back to the original broker statement line; never exported to XML, kept for audit
 */
public record SecurityEvent(
        Kind kind,
        LocalDate date,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String acquisitionType,
        BigDecimal inheritanceTax,
        BigDecimal reducedUnitPrice,
        Boolean art97Rule,
        String sourceRef) {

    public enum Kind { PURCHASE, SALE }

    public SecurityEvent {
        if (kind == null || date == null) throw new IllegalArgumentException("kind and date are required");
        if (quantity == null || quantity.signum() <= 0) throw new IllegalArgumentException("quantity must be > 0");
        if (unitPrice == null || unitPrice.signum() < 0) throw new IllegalArgumentException("unitPrice must be >= 0");
        if (kind == Kind.PURCHASE) {
            if (acquisitionType == null || acquisitionType.isBlank()) acquisitionType = "B";
            if (art97Rule != null) throw new IllegalArgumentException("art97Rule applies to sales only");
        } else if (inheritanceTax != null || reducedUnitPrice != null) {
            throw new IllegalArgumentException("inheritanceTax/reducedUnitPrice apply to purchases only");
        }
    }

    public SecurityEvent(Kind kind, LocalDate date, BigDecimal quantity, BigDecimal unitPrice,
                         String acquisitionType, String sourceRef) {
        this(kind, date, quantity, unitPrice, acquisitionType, null, null, null, sourceRef);
    }
}
