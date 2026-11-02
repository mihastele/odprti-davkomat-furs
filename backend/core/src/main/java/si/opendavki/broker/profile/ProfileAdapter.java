package si.opendavki.broker.profile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * A {@link BrokerAdapter} driven by a {@link Profile}. Same contract as every adapter: each row is mapped, skipped with a
 * reason, or failed with an error; nothing is guessed. A row that does not fit the profile (bad number, unknown action, a
 * currency affix that contradicts the currency column) is a failed or warned row, never a silently different value.
 */
public final class ProfileAdapter implements BrokerAdapter {
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}\\d");
    private static final Pattern NUMBER = Pattern.compile("^(?:([A-Za-z]{3})\\s*)?([+-]?[0-9.,]+)(?:\\s*([A-Za-z]{3}))?$");
    private static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.02");

    private final Profile p;
    private final List<DateTimeFormatter> dateFormats;

    public ProfileAdapter(Profile profile) {
        this.p = profile;
        this.dateFormats = profile.date().patterns().stream()
                .map(s -> DateTimeFormatter.ofPattern(s, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)).toList();
    }

    public Profile profile() { return p; }

    @Override public String id() { return p.id(); }

    @Override public String displayName() { return p.displayName() + (p.status() == Profile.Status.DRAFT ? " [DRAFT]" : ""); }

    private char delimiter() { return p.delimiter().charAt(0); }

    @Override public boolean accepts(StatementSource source) {
        try {
            List<Csv.Row> rows = Csv.parse(source.text(), delimiter());
            return !rows.isEmpty() && header(rows.get(0)).containsAll(p.requiredColumns());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> header(Csv.Row row) {
        return row.cells().stream().map(String::trim).toList();
    }

    private static final class RowError extends RuntimeException {
        final String code;
        RowError(String code, String message) { super(message); this.code = code; }
    }

    /** One row with column references resolved. */
    private final class Fields {
        private final Csv.Row row;
        private final List<String> header;

        Fields(Csv.Row row, List<String> header) { this.row = row; this.header = header; }

        private int index(String ref) {
            if (ref.startsWith("after:")) {
                int i = header.indexOf(ref.substring(6).trim());
                if (i < 0 || i + 1 >= header.size()) throw new RowError("MISSING_COLUMN", "column '" + ref + "' not found");
                return i + 1;
            }
            int i = header.indexOf(ref);
            if (i < 0) throw new RowError("MISSING_COLUMN", "column '" + ref + "' not found");
            return i;
        }

        String get(String ref) {
            return ref == null ? "" : row.cells().get(index(ref)).trim();
        }

        String require(String ref) {
            String v = get(ref);
            if (v.isEmpty()) throw new RowError("MISSING_FIELD", "'" + ref + "' is empty");
            return v;
        }
    }

    /** A parsed number and the currency written next to it, if any. */
    private record Amount(BigDecimal value, String currency) {}

    private Amount amount(String raw, String column) {
        String s = raw.trim();
        Matcher m = NUMBER.matcher(s);
        if (!m.matches() || (!p.number().currencyAffix() && (m.group(1) != null || m.group(3) != null))) {
            throw new RowError("INVALID_NUMBER", "'" + column + "' is not a number in this statement's format: '" + raw + "'");
        }
        String currency = m.group(1) != null ? m.group(1).toUpperCase(Locale.ROOT) : m.group(3) != null ? m.group(3).toUpperCase(Locale.ROOT) : null;
        String digits = m.group(2), dec = p.number().decimalSeparator(), grp = p.number().groupSeparator() == null ? "" : p.number().groupSeparator();
        String sign = "";
        if (digits.startsWith("+") || digits.startsWith("-")) { sign = digits.substring(0, 1).equals("-") ? "-" : ""; digits = digits.substring(1); }
        String intPart = digits, fraction = "";
        int d = digits.indexOf(dec);
        if (d >= 0) {
            if (digits.indexOf(dec, d + 1) >= 0) throw new RowError("INVALID_NUMBER", "'" + column + "' has two decimal separators: '" + raw + "'");
            intPart = digits.substring(0, d);
            fraction = digits.substring(d + 1);
        }
        if (!grp.isEmpty() && intPart.contains(grp)) {
            // thousands separators only in groups of three, so "67.57" with group '.' cannot become 6757
            if (!intPart.matches("[0-9]{1,3}(" + Pattern.quote(grp) + "[0-9]{3})+")) {
                throw new RowError("INVALID_NUMBER", "'" + column + "' has a misplaced thousands separator: '" + raw + "'");
            }
            intPart = intPart.replace(grp, "");
        }
        if (intPart.isEmpty() && fraction.isEmpty() || !intPart.matches("[0-9]*") || !fraction.matches("[0-9]*")) {
            throw new RowError("INVALID_NUMBER", "'" + column + "' is not a number in this statement's format: '" + raw + "'");
        }
        return new Amount(new BigDecimal(sign + (intPart.isEmpty() ? "0" : intPart) + (fraction.isEmpty() ? "" : "." + fraction)), currency);
    }

    private BigDecimal number(Fields f, String ref, boolean required, String expectedCurrency) {
        String raw = required ? f.require(ref) : f.get(ref);
        if (raw.isEmpty()) return null;
        Amount a = amount(raw, ref);
        if (a.currency() != null && expectedCurrency != null && !a.currency().equals(expectedCurrency)) {
            throw new RowError("CURRENCY_MISMATCH", "'" + ref + "' is written in " + a.currency() + " but the row's currency is " + expectedCurrency);
        }
        return a.value();
    }

    private LocalDate date(String raw) {
        for (DateTimeFormatter f : dateFormats) {
            try {
                TemporalAccessor t = f.parseBest(raw.trim(), OffsetDateTime::from, java.time.LocalDateTime::from, LocalDate::from);
                return t instanceof OffsetDateTime o ? o.toLocalDate() : t instanceof java.time.LocalDateTime l ? l.toLocalDate() : (LocalDate) t;
            } catch (DateTimeParseException ignored) {
                // try the next pattern
            }
        }
        throw new RowError("INVALID_DATE", "'" + p.date().column() + "' matches none of " + p.date().patterns() + ": '" + raw + "'");
    }

    @Override public ImportResult read(StatementSource source) {
        ImportResult.Builder out = ImportResult.builder(source, id());
        List<Csv.Row> rows;
        try {
            rows = Csv.parse(source.text(), delimiter());
        } catch (IllegalArgumentException e) {
            out.diagnostic(Diagnostic.error("MALFORMED_CSV", e.getMessage(), out.ref(1)));
            return out.build(0);
        }
        List<String> header = rows.isEmpty() ? List.of() : header(rows.get(0));
        List<String> missing = p.requiredColumns().stream().filter(c -> !header.contains(c)).toList();
        if (!missing.isEmpty()) {
            out.diagnostic(Diagnostic.error("MISSING_COLUMNS", "This file does not look like '" + p.displayName() + "': missing columns " + missing
                    + ". The file's header is " + header + ". Run `describe` on it and compare with the profile.", out.ref(1)));
            return out.build(0);
        }
        if (p.status() == Profile.Status.DRAFT) {
            out.diagnostic(Diagnostic.warning("DRAFT_IMPORTER", "Profile '" + p.id() + "' is a DRAFT written without a real statement"
                    + (p.note() == null ? "" : " (" + p.note() + ")") + ". Check every imported entry against the statement before relying on it.", out.ref(1)));
        }
        EntryIds ids = new EntryIds(p.id());
        int read = 0;
        for (Csv.Row r : rows.subList(1, rows.size())) {
            read++;
            try {
                if (r.cells().size() != header.size()) {
                    throw new RowError("COLUMN_COUNT", "Expected " + header.size() + " columns, found " + r.cells().size());
                }
                handle(new Fields(r, header), r.line(), out, ids);
            } catch (RowError e) {
                out.failed(r.line(), e.code, e.getMessage());
            } catch (IllegalArgumentException e) {
                out.failed(r.line(), "INVALID_VALUE", e.getMessage());
            }
        }
        return out.build(read);
    }

    private void handle(Fields f, int line, ImportResult.Builder out, EntryIds ids) {
        Profile.ActionSpec a = p.action();
        String action = a.column() == null ? null : f.get(a.column());
        if (action != null) {
            if (matches(a.skip(), action)) { out.skipped(line, "SKIPPED_ACTION", "'" + action + "' is listed as irrelevant in the profile"); return; }
            if (matches(a.unsupported(), action)) {
                out.skipped(line, "UNSUPPORTED_ACTION", "'" + action + "' has tax effects this profile does not model; enter it by hand", Severity.WARNING);
                return;
            }
        }
        if (action != null && matches(a.dividend(), action)) { dividend(f, line, out, ids); return; }

        Trade.Side side;
        if (action != null && matches(a.buy(), action)) side = Trade.Side.BUY;
        else if (action != null && matches(a.sell(), action)) side = Trade.Side.SELL;
        else if (a.column() == null && a.signDetermines()) side = null; // decided by the quantity's sign below
        else {
            out.skipped(line, "UNKNOWN_ACTION", "Action '" + action + "' is not listed in the profile", Severity.WARNING);
            return;
        }
        trade(f, line, out, ids, side);
    }

    private static boolean matches(List<String> values, String action) {
        return values != null && values.stream().anyMatch(v -> v.equalsIgnoreCase(action.trim()));
    }

    private Instrument instrument(Fields f) {
        String isin = p.instrument().isinColumn() == null ? "" : f.get(p.instrument().isinColumn()).toUpperCase(Locale.ROOT);
        if (!isin.isEmpty() && !ISIN.matcher(isin).matches()) throw new RowError("INVALID_ISIN", "malformed ISIN '" + isin + "'");
        String symbol = p.instrument().symbolColumn() == null ? "" : f.get(p.instrument().symbolColumn());
        String name = p.instrument().nameColumn() == null ? "" : f.get(p.instrument().nameColumn());
        if (isin.isEmpty() && symbol.isEmpty()) throw new RowError("MISSING_INSTRUMENT", "row has neither an ISIN nor a symbol");
        return new Instrument(isin.isEmpty() ? null : isin, symbol.isEmpty() ? isin : symbol, name.isEmpty() ? null : name, Instrument.AssetClass.OTHER);
    }

    private void trade(Fields f, int line, ImportResult.Builder out, EntryIds ids, Trade.Side declaredSide) {
        Profile.TradeSpec t = p.trade();
        Instrument ins = instrument(f);
        String raw = f.require(p.date().column());
        LocalDate date = date(raw);
        String currency = f.require(t.currencyColumn()).toUpperCase(Locale.ROOT);
        BigDecimal qty = number(f, t.quantityColumn(), true, null);
        Trade.Side side = declaredSide != null ? declaredSide : qty.signum() < 0 ? Trade.Side.SELL : Trade.Side.BUY;
        qty = qty.abs();
        if (qty.signum() == 0) throw new RowError("INVALID_QUANTITY", "quantity is zero");
        BigDecimal price = number(f, t.priceColumn(), true, currency).abs();
        BigDecimal fee = t.feeColumn() == null ? null : number(f, t.feeColumn(), false, null);
        String feeCurrency = null;
        if (fee != null) {
            fee = fee.abs();
            feeCurrency = (t.feeCurrencyColumn() != null ? f.require(t.feeCurrencyColumn()) : t.feeCurrency()).toUpperCase(Locale.ROOT);
        }
        String id = ids.derive("T", raw, ins.key(), side.name(), qty.toPlainString(), price.toPlainString());
        out.mapped(line, new Trade(id, p.broker(), p.account(), date, out.ref(line), ins, side, qty, price, currency, fee, feeCurrency));

        if ("QTY_PRICE".equals(t.totalCheck()) && t.totalColumn() != null && !f.get(t.totalColumn()).isEmpty()) {
            BigDecimal total = number(f, t.totalColumn(), true, currency).abs();
            if (qty.multiply(price).subtract(total).abs().compareTo(TOTAL_TOLERANCE) > 0) {
                out.diagnostic(Diagnostic.warning("TOTAL_MISMATCH", "The statement's total " + total.toPlainString() + " " + currency + " differs from quantity x price = "
                        + qty.multiply(price).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "; check the row", out.ref(line)));
            }
        }
    }

    private void dividend(Fields f, int line, ImportResult.Builder out, EntryIds ids) {
        Profile.DividendSpec d = p.dividend();
        if (d == null || !"GROSS".equals(d.amountIs())) {
            out.skipped(line, "UNSUPPORTED_DIVIDEND", "This statement does not give the gross dividend and the foreign tax withheld, which Doh-Div needs; "
                    + "enter the dividend by hand from the broker's tax report", Severity.WARNING);
            return;
        }
        Instrument ins = instrument(f);
        if (ins.isin() == null && p.instrument().isinColumn() != null) throw new RowError("MISSING_ISIN", "dividend row without ISIN");
        String raw = f.require(p.date().column());
        String currency = f.require(d.currencyColumn()).toUpperCase(Locale.ROOT);
        BigDecimal gross = number(f, d.grossColumn(), true, currency).abs();
        BigDecimal wht = d.withholdingColumn() == null ? BigDecimal.ZERO : number(f, d.withholdingColumn(), false, currency);
        if (wht == null) wht = BigDecimal.ZERO;
        if (wht.signum() < 0) wht = wht.abs();
        String country = ins.isin() == null ? null : ins.isin().substring(0, 2);
        String id = ids.derive("DIV", raw, ins.key(), gross.toPlainString());
        out.mapped(line, new DividendPayment(id, p.broker(), p.account(), date(raw), out.ref(line), ins, gross, wht, currency, country));
    }
}
