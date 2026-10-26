package si.opendavki.broker.ibkr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Interactive Brokers <b>Activity Statement in CSV format</b> (Reports &rarr; Statements &rarr; Activity &rarr; CSV).
 *
 * <p>The format is a sequence of {@code Section,RowType,...} lines; {@code Header} rows name the columns that following
 * {@code Data} rows use, so all fields are looked up by column name, never by position. Mapped: stock/ETF trades,
 * dividends (incl. payments in lieu), withholding tax (joined to its dividend), forward splits, security transfers.
 * Everything else is skipped with an explicit diagnostic (WARNING when it is tax-relevant but unsupported).
 * See docs/ibkr.md for the exact rules and known gaps.
 */
public final class IbkrActivityAdapter implements BrokerAdapter {
    public static final String ID = "ibkr";
    private static final String BROKER = "ibkr";

    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}\\d");
    private static final Pattern DESC_INSTRUMENT = Pattern.compile("^\\s*(.+?)\\(([A-Z0-9]{6,12})\\)(.*)$");
    private static final Pattern SPLIT = Pattern.compile("Split\\s+(\\d+)\\s+for\\s+(\\d+)");
    private static final Pattern WHT_COUNTRY = Pattern.compile("-\\s*([A-Z]{2})\\s+Tax");
    private static final Pattern DATE = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");

    /** Tax-relevant sections we recognise but do not convert: reported as WARNING per row. */
    private static final Set<String> UNSUPPORTED_SECTIONS = Set.of("Interest", "Securities Lent",
            "Stock Yield Enhancement Program Securities Lent Activity",
            "Stock Yield Enhancement Program Securities Lent Interest Details", "Grant Activity");

    @Override public String id() { return ID; }

    @Override public String displayName() { return "Interactive Brokers (Activity Statement CSV)"; }

    @Override public boolean accepts(StatementSource source) {
        try {
            List<Csv.Row> rows = Csv.parse(source.text(), ',');
            for (int i = 0; i < Math.min(rows.size(), 40); i++) {
                List<String> c = rows.get(i).cells();
                if (c.size() >= 4 && c.get(0).equals("Statement") && c.get(1).equals("Data")
                        && c.get(2).equals("BrokerName") && c.get(3).contains("Interactive Brokers")) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** One non-header line with its column names resolved. {@code fields} is null if the cells do not fit the header. */
    private record Rec(int line, String section, String kind, Map<String, String> fields, String problem) {
        String get(String name) {
            String v = fields == null ? null : fields.get(name);
            return v == null ? "" : v.trim();
        }
    }

    private record Fii(String isin, String name, String type) {}

    /** A dividend or withholding row waiting to be grouped. */
    private record Pending(Rec rec, String currency, LocalDate date, String symbol, String isin, BigDecimal amount,
                           String description, boolean inLieu) {
        String key() { return (isin != null ? isin : "SYMBOL:" + symbol) + "|" + date + "|" + currency; }
    }

    private static final class RowError extends RuntimeException {
        final String code;
        RowError(String code, String message) { super(message); this.code = code; }
    }

    @Override public ImportResult read(StatementSource source) {
        ImportResult.Builder out = ImportResult.builder(source, ID);
        List<Csv.Row> csv;
        try {
            csv = Csv.parse(source.text(), ',');
        } catch (IllegalArgumentException e) {
            out.diagnostic(Diagnostic.error("MALFORMED_CSV", e.getMessage(), out.ref(1)));
            return out.build(0);
        }

        // Pass 1: attach column names to every line; collect account and instrument information.
        Map<String, List<String>> headers = new HashMap<>();
        List<Rec> recs = new ArrayList<>();
        for (Csv.Row row : csv) {
            List<String> c = row.cells();
            String section = c.get(0).trim();
            String kind = c.size() > 1 ? c.get(1).trim() : "";
            if (kind.equals("Header")) {
                headers.put(section, c.subList(2, c.size()).stream().map(String::trim).toList());
                continue;
            }
            List<String> values = c.size() > 2 ? c.subList(2, c.size()) : List.of();
            List<String> h = headers.get(section);
            Map<String, String> fields = null;
            String problem = null;
            if (h == null) {
                problem = "data row before any header row for section '" + section + "'";
            } else if (values.size() != h.size()) {
                problem = "expected " + h.size() + " columns for section '" + section + "', found " + values.size();
            } else {
                fields = new HashMap<>();
                for (int i = 0; i < h.size(); i++) fields.putIfAbsent(h.get(i), values.get(i));
            }
            recs.add(new Rec(row.line(), section, kind, fields, problem));
        }

        String account = null;
        Map<String, Fii> fii = new HashMap<>();
        for (Rec r : recs) {
            if (r.fields() == null || !r.kind().equals("Data")) continue;
            if (r.section().equals("Account Information") && r.get("Field Name").equals("Account")) {
                if (account == null) account = r.get("Field Value");
                else if (!account.equals(r.get("Field Value"))) {
                    out.diagnostic(Diagnostic.warning("MULTIPLE_ACCOUNTS", "Statement lists several accounts; using "
                            + account + " for all entries", out.ref(r.line())));
                }
            } else if (r.section().equals("Financial Instrument Information") && r.get("Asset Category").equals("Stocks")) {
                String id = r.get("Security ID");
                fii.putIfAbsent(r.get("Symbol"), new Fii(ISIN.matcher(id).matches() ? id : null, r.get("Description"), r.get("Type")));
            }
        }
        if (account == null) {
            account = "unknown";
            out.diagnostic(Diagnostic.warning("MISSING_ACCOUNT", "No account number found in 'Account Information'; using 'unknown'", out.ref(1)));
        }

        boolean hasOrderRows = recs.stream().anyMatch(r -> r.section().equals("Trades") && r.kind().equals("Data")
                && r.get("DataDiscriminator").equals("Order"));

        EntryIds ids = new EntryIds("ibkr");
        List<Pending> dividends = new ArrayList<>();
        List<Pending> withholdings = new ArrayList<>();

        // Pass 2: row by row.
        for (Rec r : recs) {
            try {
                handle(r, out, account, fii, ids, hasOrderRows, dividends, withholdings);
            } catch (RowError e) {
                out.failed(r.line(), e.code, e.getMessage());
            } catch (IllegalArgumentException e) {
                out.failed(r.line(), "INVALID_VALUE", e.getMessage());
            }
        }
        joinDividends(out, account, fii, ids, dividends, withholdings);
        return out.build(recs.size());
    }

    private void handle(Rec r, ImportResult.Builder out, String account, Map<String, Fii> fii, EntryIds ids,
                        boolean hasOrderRows, List<Pending> dividends, List<Pending> withholdings) {
        String section = r.section();
        switch (r.kind()) {
            case "SubTotal", "Total" -> { out.skipped(r.line(), "TOTAL_ROW", "Aggregate row in '" + section + "'"); return; }
            case "Data" -> { }
            case "Notes" -> { out.skipped(r.line(), "NOTES", "Notes row in '" + section + "'"); return; }
            default -> throw new RowError("UNKNOWN_ROW_TYPE", "Row type '" + r.kind() + "' in section '" + section + "' is not Data/SubTotal/Total");
        }

        boolean relevant = Set.of("Trades", "Dividends", "Payment In Lieu Of Dividends", "Withholding Tax",
                "Corporate Actions", "Transfers").contains(section);
        if (!relevant) {
            if (UNSUPPORTED_SECTIONS.contains(section)) {
                if (r.fields() != null && r.get("Currency").startsWith("Total")) {
                    out.skipped(r.line(), "TOTAL_ROW", "Total line in '" + section + "'");
                } else {
                    out.skipped(r.line(), "UNSUPPORTED_SECTION", "'" + section + "' is tax-relevant but not supported yet; "
                            + "declare it separately", Severity.WARNING);
                }
            } else if (section.equals("Account Information") || section.equals("Financial Instrument Information")
                    || section.equals("Statement")) {
                out.skipped(r.line(), "METADATA", "Used as metadata, produces no entry");
            } else {
                out.skipped(r.line(), "NOT_TAX_RELEVANT", "Section '" + section + "' does not affect the tax declarations");
            }
            return;
        }
        if (r.fields() == null) throw new RowError("COLUMN_COUNT", r.problem());

        switch (section) {
            case "Trades" -> trade(r, out, account, fii, ids, hasOrderRows);
            case "Dividends", "Payment In Lieu Of Dividends" -> {
                Pending p = pending(r, section.startsWith("Payment"));
                if (p != null) dividends.add(p); else out.skipped(r.line(), "TOTAL_ROW", "Currency total line in '" + section + "'");
            }
            case "Withholding Tax" -> {
                Pending p = pending(r, false);
                if (p != null) withholdings.add(p); else out.skipped(r.line(), "TOTAL_ROW", "Currency total line in '" + section + "'");
            }
            case "Corporate Actions" -> corporateAction(r, out, account, fii, ids);
            case "Transfers" -> transfer(r, out, account, fii, ids);
            default -> throw new IllegalStateException(section);
        }
    }

    // ---- Trades ----------------------------------------------------------------------------------------------

    private void trade(Rec r, ImportResult.Builder out, String account, Map<String, Fii> fii, EntryIds ids, boolean hasOrderRows) {
        String disc = r.get("DataDiscriminator");
        if (disc.equals("ClosedLot") || disc.equals("Wash Sale")) {
            out.skipped(r.line(), "LOT_DETAIL", "Lot detail row; derived data, lots are rebuilt by the FIFO engine");
            return;
        }
        if (disc.equals("Trade") && hasOrderRows) {
            out.skipped(r.line(), "EXECUTION_DETAIL", "Execution detail of an 'Order' row already imported (avoids double counting)");
            return;
        }
        String category = r.get("Asset Category");
        if (category.equals("Forex")) {
            out.skipped(r.line(), "FOREX_TRADE", "Currency conversion; not a security disposal");
            return;
        }
        if (!category.equals("Stocks")) {
            out.skipped(r.line(), "UNSUPPORTED_ASSET_CATEGORY", "'" + category + "' trades are not supported (separate tax treatment): "
                    + r.get("Symbol"), Severity.WARNING);
            return;
        }
        String symbol = require(r, "Symbol");
        String raw = require(r, "Date/Time");
        LocalDate date = date(raw, "Date/Time");
        BigDecimal qty = number(require(r, "Quantity"), "Quantity");
        BigDecimal price = number(require(r, "T. Price"), "T. Price");
        String currency = require(r, "Currency");
        if (qty.signum() == 0) throw new RowError("ZERO_QUANTITY", "Trade with quantity 0");
        BigDecimal fee = r.get("Comm/Fee").isEmpty() ? BigDecimal.ZERO : number(r.get("Comm/Fee"), "Comm/Fee").abs();
        Fii info = fii.get(symbol);
        Trade.Side side = qty.signum() > 0 ? Trade.Side.BUY : Trade.Side.SELL;
        String id = ids.derive(account, "T", symbol, raw, qty.toPlainString(), price.toPlainString());
        Trade t = new Trade(id, BROKER, account, date, out.ref(r.line()), instrument(symbol, null, info), side,
                qty.abs(), price, currency, fee, currency);
        out.mapped(r.line(), t);
        if (List.of(r.get("Code").split(";")).contains("Ca")) {
            out.diagnostic(Diagnostic.warning("CANCELLED_TRADE", "Trade flagged as cancelled (code Ca); check that the "
                    + "cancelling counter-trade is in the statement", out.ref(r.line())));
        }
    }

    // ---- Dividends and withholding ---------------------------------------------------------------------------

    /** Returns null for per-currency total lines. */
    private Pending pending(Rec r, boolean inLieu) {
        String currency = r.get("Currency");
        if (!currency.matches("[A-Z]{3}")) return null;
        LocalDate date = date(require(r, "Date"), "Date");
        String desc = require(r, "Description");
        Matcher m = DESC_INSTRUMENT.matcher(desc);
        if (!m.matches()) throw new RowError("UNPARSEABLE_DESCRIPTION", "Cannot find 'SYMBOL(ISIN)' at the start of: " + desc);
        String id = m.group(2);
        return new Pending(r, currency, date, m.group(1).trim(), ISIN.matcher(id).matches() ? id : null,
                number(require(r, "Amount"), "Amount"), desc, inLieu);
    }

    private void joinDividends(ImportResult.Builder out, String account, Map<String, Fii> fii, EntryIds ids,
                               List<Pending> dividends, List<Pending> withholdings) {
        Map<String, List<Pending>> whtByKey = new LinkedHashMap<>();
        for (Pending w : withholdings) whtByKey.computeIfAbsent(w.key(), k -> new ArrayList<>()).add(w);
        Map<String, List<Pending>> divByKey = new LinkedHashMap<>();
        for (Pending d : dividends) divByKey.computeIfAbsent(d.key(), k -> new ArrayList<>()).add(d);

        for (Map.Entry<String, List<Pending>> g : divByKey.entrySet()) {
            List<Pending> rows = g.getValue();
            Pending first = rows.get(0);
            BigDecimal gross = rows.stream().map(Pending::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Pending> whts = whtByKey.remove(g.getKey());
            if (gross.signum() < 0) {
                rows.forEach(p -> out.failed(p.rec().line(), "NEGATIVE_DIVIDEND", "Dividends for " + first.symbol() + " on "
                        + first.date() + " net to " + gross.toPlainString() + " (reversal of an earlier-period dividend?); handle manually"));
                if (whts != null) whts.forEach(p -> out.failed(p.rec().line(), "WHT_WITHOUT_DIVIDEND", "Withholding tax belongs to a reversed dividend"));
                continue;
            }
            if (gross.signum() == 0) {
                rows.forEach(p -> out.skipped(p.rec().line(), "DIVIDEND_REVERSED", "Dividend and its reversal cancel out"));
                if (whts != null) whts.forEach(p -> out.skipped(p.rec().line(), "DIVIDEND_REVERSED", "Withholding of a reversed dividend"));
                continue;
            }
            BigDecimal withheld = BigDecimal.ZERO;
            String country = null;
            if (whts != null) {
                BigDecimal sum = whts.stream().map(Pending::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
                withheld = sum.negate();
                for (Pending w : whts) {
                    Matcher m = WHT_COUNTRY.matcher(w.description());
                    if (country == null && m.find()) country = m.group(1);
                }
                if (withheld.signum() < 0) {
                    whts.forEach(p -> out.failed(p.rec().line(), "WHT_NET_REFUND", "Withholding lines for " + first.symbol()
                            + " net to a refund of " + sum.toPlainString() + "; handle manually"));
                    withheld = BigDecimal.ZERO;
                    whts = null;
                }
            }
            if (country == null && first.isin() != null) country = first.isin().substring(0, 2);

            Fii info = fii.get(first.symbol());
            DividendPayment d = new DividendPayment(
                    ids.derive(account, "DIV", first.isin() != null ? first.isin() : first.symbol(), first.date().toString(), first.currency()),
                    BROKER, account, first.date(), out.ref(first.rec().line()), instrument(first.symbol(), first.isin(), info),
                    gross, withheld, first.currency(), country);
            out.mapped(first.rec().line(), d);
            for (Pending p : rows.subList(1, rows.size())) {
                out.merged(p.rec().line(), first.rec().line(), "DIVIDEND_ROWS_MERGED", "Same instrument, date and currency");
            }
            if (whts != null) {
                for (Pending w : whts) out.merged(w.rec().line(), first.rec().line(), "WITHHOLDING_JOINED", "Withholding tax joined to its dividend");
            }
            if (rows.stream().anyMatch(Pending::inLieu)) {
                out.diagnostic(Diagnostic.warning("PAYMENT_IN_LIEU", "Payment in lieu of dividend for " + first.symbol()
                        + " is treated as a dividend; confirm its tax treatment", out.ref(first.rec().line())));
            }
            if (first.isin() == null) {
                out.diagnostic(Diagnostic.warning("NON_ISIN_IDENTIFIER", "Dividend identifier of " + first.symbol()
                        + " is not an ISIN (US-entity statements use CUSIP)", out.ref(first.rec().line())));
            }
        }
        for (List<Pending> orphans : whtByKey.values()) {
            for (Pending w : orphans) {
                out.failed(w.rec().line(), "WHT_WITHOUT_DIVIDEND", "No dividend for " + w.symbol() + " on " + w.date() + " in "
                        + w.currency() + " to attach this withholding tax to (reclassification or late adjustment?)");
            }
        }
    }

    // ---- Corporate actions and transfers ---------------------------------------------------------------------

    private void corporateAction(Rec r, ImportResult.Builder out, String account, Map<String, Fii> fii, EntryIds ids) {
        if (!r.get("Asset Category").equals("Stocks")) {
            out.skipped(r.line(), "UNSUPPORTED_ASSET_CATEGORY", "Corporate action on '" + r.get("Asset Category") + "' is not supported",
                    Severity.WARNING);
            return;
        }
        String desc = require(r, "Description");
        LocalDate date = date(require(r, "Date/Time"), "Date/Time");
        Matcher m = DESC_INSTRUMENT.matcher(desc);
        if (!m.matches()) throw new RowError("UNPARSEABLE_DESCRIPTION", "Cannot find 'SYMBOL(ISIN)' at the start of: " + desc);
        String symbol = m.group(1).trim();
        String isin = ISIN.matcher(m.group(2)).matches() ? m.group(2) : null;
        Instrument ins = instrument(symbol, isin, fii.get(symbol));
        BigDecimal quantity = number(require(r, "Quantity"), "Quantity");

        Matcher s = SPLIT.matcher(desc);
        CorporateAction a;
        if (s.find() && new BigDecimal(s.group(1)).compareTo(new BigDecimal(s.group(2))) > 0 && quantity.signum() > 0) {
            a = new CorporateAction(ids.derive(account, "CA", ins.key(), date.toString(), desc), BROKER, account, date,
                    out.ref(r.line()), ins, CorporateAction.Type.SPLIT, new BigDecimal(s.group(1)), new BigDecimal(s.group(2)), desc);
        } else {
            // reverse splits, mergers, spin-offs, ISIN changes, ...: carried so the checker raises a warning
            a = new CorporateAction(ids.derive(account, "CA", ins.key(), date.toString(), desc), BROKER, account, date,
                    out.ref(r.line()), ins, CorporateAction.Type.UNSUPPORTED, null, null, desc);
        }
        out.mapped(r.line(), a);
    }

    private void transfer(Rec r, ImportResult.Builder out, String account, Map<String, Fii> fii, EntryIds ids) {
        if (!r.get("Asset Category").equals("Stocks")) {
            out.skipped(r.line(), "UNSUPPORTED_ASSET_CATEGORY", "Transfer of '" + r.get("Asset Category") + "' is not supported",
                    Severity.WARNING);
            return;
        }
        LocalDate date = date(require(r, "Date"), "Date");
        String symbol = require(r, "Symbol");
        String dir = require(r, "Direction");
        SecurityTransfer.Direction direction = dir.equalsIgnoreCase("In") ? SecurityTransfer.Direction.IN
                : dir.equalsIgnoreCase("Out") ? SecurityTransfer.Direction.OUT : null;
        if (direction == null) throw new RowError("INVALID_DIRECTION", "Transfer direction must be In or Out, got '" + dir + "'");
        BigDecimal qty = number(require(r, "Quantity"), "Quantity").abs();
        String sid = r.get("Security ID");
        Instrument ins = instrument(symbol, ISIN.matcher(sid).matches() ? sid : null, fii.get(symbol));
        String counterparty = r.get("Xfer Company").isEmpty() ? null : r.get("Xfer Company");
        SecurityTransfer t = new SecurityTransfer(ids.derive(account, "XFER", ins.key(), date.toString(), dir, qty.toPlainString()),
                BROKER, account, date, out.ref(r.line()), ins, direction, qty, null, null, null, counterparty);
        out.mapped(r.line(), t);
        if (direction == SecurityTransfer.Direction.IN) {
            out.diagnostic(Diagnostic.warning("TRANSFER_IN_NO_COST_BASIS", "Incoming transfer carries no acquisition date or cost "
                    + "basis in the statement; add it from the sending broker's records before computing gains", out.ref(r.line())));
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static Instrument instrument(String symbol, String isin, Fii info) {
        String resolved = isin != null ? isin : info != null ? info.isin() : null;
        Instrument.AssetClass cls = info != null && "ETF".equalsIgnoreCase(info.type()) ? Instrument.AssetClass.ETF : Instrument.AssetClass.STOCK;
        return new Instrument(resolved, symbol, info != null && !info.name().isEmpty() ? info.name() : symbol, cls);
    }

    private static String require(Rec r, String column) {
        String v = r.get(column);
        if (v.isEmpty()) throw new RowError("MISSING_FIELD", "'" + column + "' is empty or missing in section '" + r.section() + "'");
        return v;
    }

    private static LocalDate date(String raw, String column) {
        Matcher m = DATE.matcher(raw.trim());
        if (!m.find()) throw new RowError("INVALID_DATE", "'" + column + "' must start with yyyy-MM-dd, got '" + raw + "'");
        try {
            return LocalDate.parse(m.group(1));
        } catch (DateTimeParseException e) {
            throw new RowError("INVALID_DATE", "'" + column + "' is not a valid date: '" + raw + "'");
        }
    }

    /** IBKR writes large quantities with thousands separators ("1,000"); nothing else is accepted. */
    private static BigDecimal number(String raw, String column) {
        try {
            return new BigDecimal(raw.replace(",", ""));
        } catch (NumberFormatException e) {
            throw new RowError("INVALID_NUMBER", "'" + column + "' is not a number: '" + raw + "'");
        }
    }
}
