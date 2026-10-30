package si.opendavki.broker.trading212;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.Csv;
import si.opendavki.broker.EntryIds;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.ledger.*;
import si.opendavki.ledger.Diagnostic.Severity;

/**
 * Trading 212 <b>History export (CSV)</b>: one header row, one row per action. Columns are looked up by name.
 *
 * <p>Semantics (checked against a real export, see docs/trading212.md):
 * <ul>
 *   <li>Trades: {@code Price / share} is in {@code Currency (Price / share)} (GBX = pence, converted to GBP); the
 *       {@code Exchange rate} is price-currency units per EUR; {@code Total} is in the account currency and includes the
 *       conversion fee for buys and is net of it for sells. The adapter keeps the original price and currency (the tax
 *       engine converts with the configured rate source) and uses {@code Total} and the broker's rate only as a consistency
 *       check, reported as a WARNING.</li>
 *   <li>Dividends: {@code Price / share} is the dividend per share, {@code Exchange rate} is EUR per dividend-currency
 *       unit, {@code Withholding tax} is in the dividend currency. Gross = shares &times; per share.</li>
 * </ul>
 * Everything else is skipped with a diagnostic (WARNING when tax-relevant but unsupported).
 */
public final class Trading212Adapter implements BrokerAdapter {
    public static final String ID = "trading212";
    private static final String BROKER = "trading212";
    private static final String ACCOUNT = "default";

    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}\\d");
    private static final Pattern TRADE = Pattern.compile("^(?:Market|Limit|Stop|Stop limit) (buy|sell)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIVIDEND = Pattern.compile("^Dividend \\((.+)\\)$");
    private static final Pattern DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})[ T]");
    private static final Pattern ETF_NAME = Pattern.compile("\\((Acc|Dist)\\)\\s*$");

    private static final List<String> REQUIRED = List.of("Action", "ISIN", "Ticker", "No. of shares", "Price / share");
    /** Columns that carry cash movements or other things with no effect on securities taxation. */
    private static final Set<String> CASH_ACTIONS = Set.of("deposit", "withdrawal", "currency conversion", "card debit", "card credit");
    /** Income labels inside "Dividend (...)" that are plain dividends. */
    private static final Set<String> DIVIDEND_LABELS = Set.of("dividend", "ordinary", "dividend manufactured payment");

    private static final BigDecimal TRADE_TOTAL_TOLERANCE = new BigDecimal("0.02");
    private static final BigDecimal DIVIDEND_TOTAL_TOLERANCE = new BigDecimal("0.01");

    @Override public String id() { return ID; }

    @Override public String displayName() { return "Trading 212 (history CSV)"; }

    @Override public boolean accepts(StatementSource source) {
        try {
            List<Csv.Row> rows = Csv.parse(source.text(), ',');
            if (rows.isEmpty()) return false;
            List<String> header = rows.get(0).cells().stream().map(String::trim).toList();
            return header.containsAll(REQUIRED) && timeColumn(header) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String timeColumn(List<String> header) {
        return header.contains("Time (UTC)") ? "Time (UTC)" : header.contains("Time") ? "Time" : null;
    }

    private static final class RowError extends RuntimeException {
        final String code;
        RowError(String code, String message) { super(message); this.code = code; }
    }

    private static final class Fields {
        private final Csv.Row row;
        private final Map<String, Integer> col;
        Fields(Csv.Row row, Map<String, Integer> col) { this.row = row; this.col = col; }

        String get(String name) {
            Integer i = col.get(name);
            return i == null ? "" : row.cells().get(i).trim();
        }

        String require(String name) {
            String v = get(name);
            if (v.isEmpty()) throw new RowError("MISSING_FIELD", "'" + name + "' is empty or missing");
            return v;
        }

        BigDecimal number(String name, boolean required) {
            String v = required ? require(name) : get(name);
            if (v.isEmpty()) return null;
            try {
                return new BigDecimal(v);
            } catch (NumberFormatException e) {
                throw new RowError("INVALID_NUMBER", "'" + name + "' is not a number: '" + v + "'");
            }
        }
    }

    @Override public ImportResult read(StatementSource source) {
        ImportResult.Builder out = ImportResult.builder(source, ID);
        List<Csv.Row> rows;
        try {
            rows = Csv.parse(source.text(), ',');
        } catch (IllegalArgumentException e) {
            out.diagnostic(Diagnostic.error("MALFORMED_CSV", e.getMessage(), out.ref(1)));
            return out.build(0);
        }
        List<String> header = rows.isEmpty() ? List.of() : rows.get(0).cells().stream().map(String::trim).toList();
        if (!header.containsAll(REQUIRED) || timeColumn(header) == null) {
            out.diagnostic(Diagnostic.error("MISSING_COLUMNS", "Header must contain " + REQUIRED + " and 'Time (UTC)'", out.ref(1)));
            return out.build(0);
        }
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) col.putIfAbsent(header.get(i), i);
        List<String> unknownFeeColumns = header.stream().filter(Trading212Adapter::looksLikeUnhandledCost).toList();

        EntryIds ids = new EntryIds("t212");
        int read = 0;
        for (Csv.Row r : rows.subList(1, rows.size())) {
            read++;
            try {
                if (r.cells().size() != header.size()) {
                    throw new RowError("COLUMN_COUNT", "Expected " + header.size() + " columns, found " + r.cells().size());
                }
                handle(new Fields(r, col), timeColumn(header), r.line(), out, ids, unknownFeeColumns);
            } catch (RowError e) {
                out.failed(r.line(), e.code, e.getMessage());
            } catch (IllegalArgumentException e) {
                out.failed(r.line(), "INVALID_VALUE", e.getMessage());
            }
        }
        return out.build(read);
    }

    /** A cost column we do not understand (anything with "fee" or "tax" in its name besides the ones handled). */
    private static boolean looksLikeUnhandledCost(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("currency (") || n.equals("currency conversion fee") || n.equals("withholding tax")) return false;
        return n.contains("fee") || n.contains("tax") || n.contains("duty");
    }

    private void handle(Fields f, String timeColumn, int line, ImportResult.Builder out, EntryIds ids, List<String> unknownFeeColumns) {
        String action = f.require("Action");
        String lower = action.toLowerCase(Locale.ROOT);

        Matcher trade = TRADE.matcher(action);
        if (trade.matches()) {
            trade(f, timeColumn, line, out, ids, trade.group(1).equalsIgnoreCase("buy"), unknownFeeColumns);
            return;
        }
        Matcher dividend = DIVIDEND.matcher(action);
        if (dividend.matches()) {
            dividend(f, timeColumn, line, out, ids, dividend.group(1));
            return;
        }
        if (CASH_ACTIONS.contains(lower)) {
            out.skipped(line, "CASH_MOVEMENT", "'" + action + "' moves cash only");
        } else if (lower.contains("split")) {
            corporateAction(f, timeColumn, line, out, ids, action);
        } else {
            String why = lower.contains("interest") ? "Interest income belongs to a different tax form (not supported yet)"
                    : "Action '" + action + "' is not recognised";
            out.skipped(line, lower.contains("interest") ? "UNSUPPORTED_ACTION" : "UNKNOWN_ACTION", why, Severity.WARNING);
        }
    }

    // ---- trades -----------------------------------------------------------------------------------------------

    private void trade(Fields f, String timeColumn, int line, ImportResult.Builder out, EntryIds ids, boolean buy, List<String> unknownFeeColumns) {
        Instrument ins = instrument(f);
        LocalDate date = date(f.require(timeColumn), timeColumn);
        BigDecimal qty = f.number("No. of shares", true);
        if (qty.signum() <= 0) throw new RowError("INVALID_QUANTITY", "No. of shares must be positive, got " + qty.toPlainString());
        BigDecimal rawPrice = f.number("Price / share", true);
        String rawCurrency = f.require("Currency (Price / share)");
        BigDecimal rate = f.number("Exchange rate", false);
        BigDecimal fee = f.number("Currency conversion fee", false);
        String feeCurrency = fee == null ? null : f.require("Currency (Currency conversion fee)");

        BigDecimal price = rawPrice;
        String currency = rawCurrency;
        if (rawCurrency.equals("GBX")) { // pence: 1 GBP = 100 GBX, and ECB publishes GBP
            price = rawPrice.movePointLeft(2);
            currency = "GBP";
        }

        String id = f.get("ID").isEmpty() ? ids.derive("T", f.get(timeColumn), ins.key(), qty.toPlainString(), rawPrice.toPlainString()) : f.get("ID");
        Trade t = new Trade(id, BROKER, ACCOUNT, date, out.ref(line), ins, buy ? Trade.Side.BUY : Trade.Side.SELL,
                qty, price, currency, fee, feeCurrency);
        out.mapped(line, t);

        checkTradeTotal(f, line, out, buy, qty, rawPrice, rate, fee, feeCurrency);
        for (String column : unknownFeeColumns) {
            if (!f.get(column).isEmpty()) {
                out.diagnostic(Diagnostic.warning("UNKNOWN_COST_COLUMN", "Column '" + column + "' has a value (" + f.get(column)
                        + ") that is not included in the cost or proceeds of this trade", out.ref(line)));
            }
        }
    }

    /** Trading 212's own arithmetic as a plausibility check of what we parsed; never changes the entry. */
    private void checkTradeTotal(Fields f, int line, ImportResult.Builder out, boolean buy, BigDecimal qty, BigDecimal rawPrice,
                                 BigDecimal rate, BigDecimal fee, String feeCurrency) {
        BigDecimal total = f.get("Total").isEmpty() ? null : f.number("Total", true);
        String totalCurrency = f.get("Currency (Total)");
        if (total == null || rate == null || rate.signum() <= 0) return;
        if (fee != null && !totalCurrency.equals(feeCurrency)) return;
        BigDecimal gross = qty.multiply(rawPrice).divide(rate, 10, java.math.RoundingMode.HALF_UP);
        BigDecimal feeInTotal = fee == null ? BigDecimal.ZERO : fee;
        BigDecimal expected = buy ? gross.add(feeInTotal) : gross.subtract(feeInTotal);
        if (expected.subtract(total).abs().compareTo(TRADE_TOTAL_TOLERANCE) > 0) {
            out.diagnostic(Diagnostic.warning("TOTAL_MISMATCH", "Trading 212 reports a total of " + total.toPlainString() + " "
                    + totalCurrency + " but shares x price / exchange rate " + (buy ? "+" : "-") + " fee gives "
                    + expected.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "; check the row", out.ref(line)));
        }
    }

    // ---- dividends --------------------------------------------------------------------------------------------

    private void dividend(Fields f, String timeColumn, int line, ImportResult.Builder out, EntryIds ids, String label) {
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        if (!DIVIDEND_LABELS.contains(lowerLabel)) {
            out.skipped(line, "UNSUPPORTED_DIVIDEND_TYPE", "Dividend type '" + label + "' is not imported (return of capital, bonus and "
                    + "similar are not plain dividend income); declare it by hand", Severity.WARNING);
            return;
        }
        Instrument ins = instrument(f);
        if (ins.isin() == null) throw new RowError("MISSING_ISIN", "Dividend row without a valid ISIN");
        LocalDate date = date(f.require(timeColumn), timeColumn);
        BigDecimal qty = f.number("No. of shares", true);
        BigDecimal perShare = f.number("Price / share", true);
        String rawCurrency = f.require("Currency (Price / share)");
        BigDecimal rate = f.number("Exchange rate", false);
        BigDecimal wht = f.number("Withholding tax", false);
        String whtCurrency = f.get("Currency (Withholding tax)");

        BigDecimal gross = qty.multiply(perShare);
        String currency = rawCurrency;
        if (rawCurrency.equals("GBX")) {
            gross = gross.movePointLeft(2);
            currency = "GBP";
        }
        if (wht == null) wht = BigDecimal.ZERO;
        if (wht.signum() < 0) throw new RowError("NEGATIVE_WITHHOLDING", "Withholding tax " + wht.toPlainString()
                + " is negative (a refund?); handle manually");
        if (wht.signum() != 0 && !whtCurrency.equals(rawCurrency)) {
            throw new RowError("WITHHOLDING_CURRENCY", "Withholding tax is in " + whtCurrency + " but the dividend is in " + rawCurrency);
        }
        BigDecimal withholding = rawCurrency.equals("GBX") ? wht.movePointLeft(2) : wht;

        String id = ids.derive("DIV", f.get(timeColumn), ins.key(), qty.toPlainString(), perShare.toPlainString());
        DividendPayment d = new DividendPayment(id, BROKER, ACCOUNT, date, out.ref(line), ins, gross, withholding, currency,
                ins.isin().substring(0, 2));
        out.mapped(line, d);

        checkDividendTotal(f, line, out, rawCurrency, qty.multiply(perShare), rate);
        if (lowerLabel.equals("dividend manufactured payment")) {
            out.diagnostic(Diagnostic.warning("PAYMENT_IN_LIEU", "Manufactured payment for " + ins.key()
                    + " (paid instead of a dividend on lent shares) is treated as a dividend; confirm its tax treatment", out.ref(line)));
        }
    }

    /** Total is the gross dividend in EUR rounded to cents, rate = EUR per unit of the dividend currency. */
    private void checkDividendTotal(Fields f, int line, ImportResult.Builder out, String currency, BigDecimal gross, BigDecimal rate) {
        if (currency.equals("GBX") || rate == null || f.get("Total").isEmpty() || !f.get("Currency (Total)").equals("EUR")) return;
        BigDecimal expected = gross.multiply(rate);
        BigDecimal total = f.number("Total", true);
        if (expected.subtract(total).abs().compareTo(DIVIDEND_TOTAL_TOLERANCE) > 0) {
            out.diagnostic(Diagnostic.warning("TOTAL_MISMATCH", "Trading 212 reports a dividend total of " + total.toPlainString()
                    + " EUR but shares x dividend per share x exchange rate gives "
                    + expected.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "; check the row", out.ref(line)));
        }
    }

    // ---- corporate actions ------------------------------------------------------------------------------------

    private void corporateAction(Fields f, String timeColumn, int line, ImportResult.Builder out, EntryIds ids, String action) {
        Instrument ins = instrument(f);
        LocalDate date = date(f.require(timeColumn), timeColumn);
        String description = action + (f.get("No. of shares").isEmpty() ? "" : " (" + f.get("No. of shares") + " shares)");
        // The export does not give the ratio, so a split cannot be applied automatically: carry it as unsupported.
        CorporateAction a = new CorporateAction(ids.derive("CA", f.get(timeColumn), ins.key(), action, f.get("No. of shares")), BROKER, ACCOUNT,
                date, out.ref(line), ins, CorporateAction.Type.UNSUPPORTED, null, null, description);
        out.mapped(line, a);
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static Instrument instrument(Fields f) {
        String isin = f.get("ISIN").toUpperCase(Locale.ROOT);
        if (isin.isEmpty() || !ISIN.matcher(isin).matches()) {
            throw new RowError("INVALID_ISIN", "ISIN is empty or malformed: '" + f.get("ISIN") + "'");
        }
        String name = f.get("Name");
        Instrument.AssetClass cls = ETF_NAME.matcher(name).find() ? Instrument.AssetClass.ETF : Instrument.AssetClass.STOCK;
        return new Instrument(isin, f.get("Ticker").isEmpty() ? isin : f.get("Ticker"), name.isEmpty() ? null : name, cls);
    }

    /** The UTC date of the action (the export is in UTC). */
    private static LocalDate date(String raw, String column) {
        Matcher m = DATE.matcher(raw.trim() + " ");
        if (!m.find()) throw new RowError("INVALID_DATE", "'" + column + "' must start with yyyy-MM-dd, got '" + raw + "'");
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            throw new RowError("INVALID_DATE", "'" + column + "' is not a valid date: '" + raw + "'");
        }
    }
}
