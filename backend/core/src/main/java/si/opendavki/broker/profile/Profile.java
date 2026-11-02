package si.opendavki.broker.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * A declarative description of a one-row-per-event CSV statement: which columns hold what, how dates and numbers are written
 * and which values of the action column mean buy, sell, dividend or "ignore". See docs/profiles.md.
 *
 * <p>A profile whose {@code status} is {@code DRAFT} was written from a recalled or documented layout without a real file; its
 * {@code id} must end in {@code -draft}, every import warns, and the report states that DRAFT data was used.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record Profile(int formatVersion, String id, String displayName, Status status, String note, String broker, String account,
                      String delimiter, List<String> requiredColumns, DateSpec date, NumberSpec number, InstrumentSpec instrument,
                      ActionSpec action, TradeSpec trade, DividendSpec dividend) {

    public enum Status { DRAFT, VERIFIED }

    /** @param patterns {@link java.time.format.DateTimeFormatter} patterns tried in order; only the date part is kept */
    public record DateSpec(String column, List<String> patterns) {}

    /**
     * @param decimalSeparator  "." or ","
     * @param groupSeparator    "," or "." or "" (none); only accepted in groups of three digits, so "67.57" cannot be misread as 6757
     * @param currencyAffix     allow a three-letter currency code before or after the number ("USD 12.30"); it must equal the row's currency
     */
    public record NumberSpec(String decimalSeparator, String groupSeparator, boolean currencyAffix) {}

    /** Column references are a header name, or {@code after:<name>} for the column right after it (statements with unnamed currency columns). */
    public record InstrumentSpec(String isinColumn, String symbolColumn, String nameColumn) {}

    /**
     * @param column        the column holding the action/type, or null if buy/sell follows from the sign of the quantity
     * @param signDetermines with no action column: positive quantity = buy, negative = sell
     */
    public record ActionSpec(String column, boolean signDetermines, List<String> buy, List<String> sell, List<String> dividend,
                             List<String> skip, List<String> unsupported) {}

    /**
     * @param feeCurrency literal currency of the fee column when the statement does not give one per row
     * @param totalColumn optional: Total value as the broker computed it, used with {@code totalCheck}
     * @param totalCheck  NONE, or QTY_PRICE (warn when |quantity x price - |total|| exceeds the tolerance, for statements whose total is in the price currency)
     */
    public record TradeSpec(String quantityColumn, String priceColumn, String currencyColumn, String feeColumn, String feeCurrencyColumn,
                            String feeCurrency, String totalColumn, String totalCheck) {}

    /**
     * @param amountIs GROSS: {@code grossColumn} is the dividend before withholding; any other value means the statement does not
     *                 give a gross amount and dividend rows are not imported
     */
    public record DividendSpec(String amountIs, String grossColumn, String withholdingColumn, String currencyColumn) {}

    /** Throws IllegalArgumentException naming the problem; called by the loader so a bad profile never runs. */
    public Profile {
        if (formatVersion != 1) throw new IllegalArgumentException("profile formatVersion must be 1, got " + formatVersion);
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]*")) throw new IllegalArgumentException("profile id must be lowercase letters, digits and dashes: " + id);
        if (status == null) throw new IllegalArgumentException("profile status (DRAFT or VERIFIED) is required");
        if ((status == Status.DRAFT) != id.endsWith("-draft")) {
            throw new IllegalArgumentException("a DRAFT profile's id must end in '-draft' and a VERIFIED one's must not: " + id);
        }
        if (broker == null || broker.isBlank()) throw new IllegalArgumentException("profile broker is required");
        if (account == null || account.isBlank()) account = "default";
        if (delimiter == null || delimiter.length() != 1) delimiter = ",";
        if (requiredColumns == null || requiredColumns.isEmpty()) throw new IllegalArgumentException("profile requiredColumns must list the columns that identify the layout");
        if (date == null || date.column() == null || date.patterns() == null || date.patterns().isEmpty()) {
            throw new IllegalArgumentException("profile date needs a column and at least one pattern");
        }
        if (number == null) number = new NumberSpec(".", "", false);
        if (!List.of(".", ",").contains(number.decimalSeparator())) throw new IllegalArgumentException("number.decimalSeparator must be '.' or ','");
        String g = number.groupSeparator() == null ? "" : number.groupSeparator();
        if (!List.of("", ".", ",").contains(g) || g.equals(number.decimalSeparator())) throw new IllegalArgumentException("number.groupSeparator must be '', '.' or ',' and differ from the decimal separator");
        if (instrument == null || (instrument.isinColumn() == null && instrument.symbolColumn() == null)) {
            throw new IllegalArgumentException("profile instrument needs an isinColumn or a symbolColumn");
        }
        if (action == null) throw new IllegalArgumentException("profile action is required");
        if (action.column() == null && !action.signDetermines()) throw new IllegalArgumentException("action needs a column, or signDetermines=true");
        if (trade == null || trade.quantityColumn() == null || trade.priceColumn() == null) throw new IllegalArgumentException("profile trade needs quantityColumn and priceColumn");
        if (trade.currencyColumn() == null) throw new IllegalArgumentException("profile trade needs a currencyColumn");
        if (trade.feeColumn() != null && trade.feeCurrencyColumn() == null && trade.feeCurrency() == null) {
            throw new IllegalArgumentException("a feeColumn needs a feeCurrencyColumn or a literal feeCurrency");
        }
        if (dividend != null && "GROSS".equals(dividend.amountIs()) && (dividend.grossColumn() == null || dividend.currencyColumn() == null)) {
            throw new IllegalArgumentException("dividend with amountIs GROSS needs grossColumn and currencyColumn");
        }
    }
}
