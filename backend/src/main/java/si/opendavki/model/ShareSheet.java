package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** PLD sheet: shares in companies, cooperatives and other entities (values, not quantities). */
public record ShareSheet(String name, Boolean foreignCompany, String divestmentTaxNumber,
                         Boolean subsequentPayments, List<SubsequentPayment> subsequentPaymentRows,
                         List<Row> rows) {

    public record SubsequentPayment(String taxNumber, LocalDate date, BigDecimal amount) {}

    /**
     * @param value        purchase: acquisition value (F3)
     * @param inheritanceTax purchase: inheritance/gift tax paid (F4)
     * @param percent      sale: % of the holding disposed of (F6)
     * @param saleValue    sale: value at disposal (F8)
     * @param art97Rule    sale: rule of art. 97(2) in connection with (5) ZDoh-2 (F9)
     * @param balance      holding state after the row (F7)
     */
    public record Row(SecurityEvent.Kind kind, LocalDate date, String acquisitionType, BigDecimal value,
                      BigDecimal inheritanceTax, BigDecimal percent, BigDecimal saleValue, Boolean art97Rule,
                      BigDecimal balance, String sourceRef) {
        public Row {
            if (kind == null || date == null) throw new IllegalArgumentException("kind and date are required");
            if (kind == SecurityEvent.Kind.PURCHASE) {
                if (value == null) throw new IllegalArgumentException("share purchase needs value");
                if (acquisitionType == null || acquisitionType.isBlank()) acquisitionType = "B";
            } else if (percent == null || saleValue == null) {
                throw new IllegalArgumentException("share sale needs percent and saleValue");
            }
        }
    }

    public ShareSheet {
        subsequentPaymentRows = subsequentPaymentRows == null ? List.of() : List.copyOf(subsequentPaymentRows);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
