package si.opendavki.broker.generic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.Csv;
import si.opendavki.broker.EntryIds;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.ledger.*;

/**
 * Reference adapter reading OpenDavki's own flat CSV (see docs/generic-csv.md). It is the format to convert to when no
 * dedicated adapter exists yet, and the worked example for adapter authors.
 */
public final class GenericCsvAdapter implements BrokerAdapter {
    public static final String ID = "generic";
    private static final List<String> REQUIRED = List.of("type", "date", "isin", "symbol", "quantity", "price", "currency");

    @Override public String id() { return ID; }

    @Override public String displayName() { return "Generic CSV (OpenDavki format)"; }

    @Override public boolean accepts(StatementSource source) {
        try {
            List<String> header = header(Csv.parse(source.text(), ','));
            return header != null && header.containsAll(REQUIRED);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<String> header(List<Csv.Row> rows) {
        for (Csv.Row r : rows) {
            if (!isComment(r)) return r.cells().stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        }
        return null;
    }

    private static boolean isComment(Csv.Row r) {
        return r.cells().get(0).trim().startsWith("#");
    }

    /** A row-level problem; becomes a failed row with an ERROR diagnostic. */
    private static final class RowError extends RuntimeException {
        final String code;
        RowError(String code, String message) { super(message); this.code = code; }
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
        List<String> header = header(rows);
        if (header == null || !header.containsAll(REQUIRED)) {
            out.diagnostic(Diagnostic.error("MISSING_COLUMNS", "Header must contain " + REQUIRED, out.ref(1)));
            return out.build(0);
        }
        int headerLine = rows.stream().filter(r -> !isComment(r)).findFirst().orElseThrow().line();
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.size(); i++) col.putIfAbsent(header.get(i), i);

        EntryIds ids = new EntryIds("g");
        int read = 0;
        for (Csv.Row r : rows) {
            if (r.line() == headerLine) continue;
            read++;
            if (isComment(r)) {
                out.skipped(r.line(), "COMMENT", "Comment row");
                continue;
            }
            try {
                if (r.cells().size() != header.size()) {
                    throw new RowError("COLUMN_COUNT", "Expected " + header.size() + " columns, found " + r.cells().size());
                }
                out.mapped(r.line(), parseRow(new Fields(r, col), out.ref(r.line()), ids));
            } catch (RowError e) {
                out.failed(r.line(), e.code, e.getMessage());
            } catch (IllegalArgumentException e) {
                out.failed(r.line(), "INVALID_VALUE", e.getMessage());
            }
        }
        return out.build(read);
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
            if (v.isEmpty()) throw new RowError("MISSING_FIELD", "'" + name + "' is required for this row type");
            return v;
        }

        LocalDate date(String name, boolean required) {
            String v = required ? require(name) : get(name);
            if (v.isEmpty()) return null;
            try { return LocalDate.parse(v); }
            catch (DateTimeParseException e) { throw new RowError("INVALID_DATE", "'" + name + "' must be yyyy-MM-dd, got '" + v + "'"); }
        }

        BigDecimal number(String name, boolean required) {
            String v = required ? require(name) : get(name);
            if (v.isEmpty()) return null;
            try { return new BigDecimal(v); }
            catch (NumberFormatException e) { throw new RowError("INVALID_NUMBER", "'" + name + "' is not a plain decimal number: '" + v + "'"); }
        }
    }

    private static LedgerEntry[] parseRow(Fields f, SourceRef ref, EntryIds ids) {
        String type = f.require("type").toUpperCase(Locale.ROOT);
        LocalDate date = f.date("date", true);
        String broker = orDefault(f.get("broker"), "generic");
        String account = orDefault(f.get("account"), "default");
        Instrument.AssetClass cls;
        try {
            cls = f.get("asset_class").isEmpty() ? Instrument.AssetClass.OTHER
                    : Instrument.AssetClass.valueOf(f.get("asset_class").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RowError("INVALID_ASSET_CLASS", "asset_class must be one of STOCK, ETF, FUND, OTHER");
        }
        Instrument ins = new Instrument(f.get("isin").toUpperCase(Locale.ROOT), f.get("symbol"), f.get("name"), cls);
        String currency = f.get("currency").toUpperCase(Locale.ROOT);
        String id = f.get("id");

        switch (type) {
            case "BUY", "SELL" -> {
                BigDecimal qty = f.number("quantity", true), price = f.number("price", true), fee = f.number("fee", false);
                String feeCur = f.get("fee_currency").toUpperCase(Locale.ROOT);
                if (id.isEmpty()) id = ids.derive(broker, account, type, date.toString(), ins.key(), qty.toPlainString(), price.toPlainString());
                return new LedgerEntry[]{new Trade(id, broker, account, date, ref, ins,
                        type.equals("BUY") ? Trade.Side.BUY : Trade.Side.SELL, qty, price, currency, fee, feeCur)};
            }
            case "DIVIDEND" -> {
                BigDecimal gross = f.number("gross", true), wht = f.number("withholding", false);
                String country = f.get("source_country").toUpperCase(Locale.ROOT);
                if (id.isEmpty()) id = ids.derive(broker, account, type, date.toString(), ins.key(), gross.toPlainString());
                return new LedgerEntry[]{new DividendPayment(id, broker, account, date, ref, ins, gross, wht, currency,
                        country.isEmpty() ? null : country)};
            }
            case "SPLIT" -> {
                BigDecimal nw = f.number("split_new", true), old = f.number("split_old", true);
                if (id.isEmpty()) id = ids.derive(broker, account, type, date.toString(), ins.key(), nw.toPlainString(), old.toPlainString());
                return new LedgerEntry[]{new CorporateAction(id, broker, account, date, ref, ins,
                        CorporateAction.Type.SPLIT, nw, old, f.get("description"))};
            }
            case "CONVERSION" -> {
                BigDecimal nw = f.number("split_new", true), old = f.number("split_old", true);
                String newIsin = f.require("new_isin").toUpperCase(Locale.ROOT);
                String newSymbol = f.get("new_symbol").isEmpty() ? newIsin : f.get("new_symbol");
                Instrument target = new Instrument(newIsin, newSymbol, f.get("new_name").isEmpty() ? null : f.get("new_name"), cls);
                if (id.isEmpty()) id = ids.derive(broker, account, type, date.toString(), ins.key(), newIsin, nw.toPlainString(), old.toPlainString());
                return new LedgerEntry[]{new CorporateAction(id, broker, account, date, ref, ins, CorporateAction.Type.CONVERSION,
                        nw, old, f.get("description"), target)};
            }
            case "CORPORATE_ACTION" -> {
                if (id.isEmpty()) id = ids.derive(broker, account, type, date.toString(), ins.key(), f.get("description"));
                return new LedgerEntry[]{new CorporateAction(id, broker, account, date, ref, ins,
                        CorporateAction.Type.UNSUPPORTED, null, null, orDefault(f.get("description"), "unspecified"))};
            }
            case "TRANSFER_IN", "TRANSFER_OUT" -> {
                BigDecimal qty = f.number("quantity", true), basis = f.number("price", false);
                LocalDate acquired = f.date("acquisition_date", false);
                SecurityTransfer.Direction dir = type.equals("TRANSFER_IN") ? SecurityTransfer.Direction.IN : SecurityTransfer.Direction.OUT;
                if (id.isEmpty()) id = ids.derive(broker, account, type, date.toString(), ins.key(), qty.toPlainString());
                return new LedgerEntry[]{new SecurityTransfer(id, broker, account, date, ref, ins, dir, qty, acquired,
                        basis, basis == null ? null : currency, f.get("counterparty").isEmpty() ? null : f.get("counterparty"))};
            }
            default -> throw new RowError("UNKNOWN_TYPE", "Unknown row type '" + type
                    + "' (expected BUY, SELL, DIVIDEND, SPLIT, CONVERSION, CORPORATE_ACTION, TRANSFER_IN, TRANSFER_OUT)");
        }
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isEmpty() ? d : v;
    }
}
