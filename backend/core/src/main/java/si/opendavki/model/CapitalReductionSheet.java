package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** PLVPZOK sheet: securities whose share capital was reduced while the quantity stayed unchanged. */
public record CapitalReductionSheet(String isin, String code, String name, String resolution,
                                    LocalDate resolutionDate, List<Row> rows) {

    /**
     * @param quantity      purchase: quantity (F3)
     * @param unitPrice     purchase: unit cost (F4)
     * @param percent       sale: % of capital reduction (F6)
     * @param payout        sale: value paid out on the capital reduction (F7)
     */
    public record Row(SecurityEvent.Kind kind, LocalDate date, String acquisitionType,
                      BigDecimal quantity, BigDecimal unitPrice, BigDecimal percent, BigDecimal payout,
                      String sourceRef) {
        public Row {
            if (kind == null || date == null) throw new IllegalArgumentException("kind and date are required");
            if (kind == SecurityEvent.Kind.PURCHASE) {
                if (quantity == null || unitPrice == null) throw new IllegalArgumentException("purchase needs quantity and unitPrice");
                if (acquisitionType == null || acquisitionType.isBlank()) acquisitionType = "B";
            } else if (percent == null || payout == null) {
                throw new IllegalArgumentException("capital reduction row needs percent and payout");
            }
        }
    }

    public CapitalReductionSheet {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
