package si.opendavki.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import si.opendavki.broker.Csv;

/**
 * User-maintained corrections for instruments, applied to a ledger: supply a missing ISIN (for example for statements that
 * identify securities by CUSIP) and fix the asset class (which decides the fund flag of Doh-KDVP) or the name.
 *
 * <p>File format, CSV with a header row (comment rows start with {@code #}):
 * <pre>
 * match,isin,asset_class,name
 * AAPL,US0378331005,STOCK,Apple Inc.
 * IE00B4ND3602,,ETF,
 * </pre>
 * {@code match} is an ISIN, or a symbol for an instrument that has no ISIN. Empty cells change nothing. The file is the
 * user's assertion: the master never guesses an ISIN, and an ISIN that contradicts the statement is reported, not applied.
 */
public final class InstrumentMaster {
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}\\d");

    private record Row(int line, String match, String isin, Instrument.AssetClass assetClass, String name) {}

    private final String file;
    private final List<Row> rows;

    private InstrumentMaster(String file, List<Row> rows) {
        this.file = file;
        this.rows = rows;
    }

    /** {@code OPENDAVKI_INSTRUMENTS_FILE}: the master file if configured. */
    public static Optional<InstrumentMaster> fromEnvironment(Map<String, String> env) throws IOException {
        String path = env.getOrDefault("OPENDAVKI_INSTRUMENTS_FILE", "").trim();
        if (path.isEmpty()) return Optional.empty();
        Path p = Path.of(path);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("OPENDAVKI_INSTRUMENTS_FILE does not exist: " + path);
        return Optional.of(parse(Files.readString(p, StandardCharsets.UTF_8), p.getFileName().toString()));
    }

    public static InstrumentMaster parse(String csv, String fileName) {
        List<Csv.Row> all = Csv.parse(csv, ',').stream().filter(r -> !r.cells().get(0).trim().startsWith("#")).toList();
        if (all.isEmpty()) return new InstrumentMaster(fileName, List.of());
        List<String> header = all.get(0).cells().stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        int iMatch = header.indexOf("match"), iIsin = header.indexOf("isin"), iClass = header.indexOf("asset_class"), iName = header.indexOf("name");
        if (iMatch < 0) throw new IllegalArgumentException(fileName + ": header must contain a 'match' column");
        List<Row> rows = new ArrayList<>();
        for (Csv.Row r : all.subList(1, all.size())) {
            String where = fileName + ":" + r.line();
            if (r.cells().size() != header.size()) throw new IllegalArgumentException(where + ": expected " + header.size() + " columns");
            String match = cell(r, iMatch);
            if (match.isEmpty()) throw new IllegalArgumentException(where + ": 'match' is empty");
            String isin = cell(r, iIsin).toUpperCase(Locale.ROOT);
            if (!isin.isEmpty() && !ISIN.matcher(isin).matches()) throw new IllegalArgumentException(where + ": invalid ISIN '" + isin + "'");
            String cls = cell(r, iClass).toUpperCase(Locale.ROOT);
            Instrument.AssetClass assetClass = null;
            if (!cls.isEmpty()) {
                try {
                    assetClass = Instrument.AssetClass.valueOf(cls);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(where + ": asset_class must be one of STOCK, ETF, FUND, OTHER, got '" + cls + "'");
                }
            }
            rows.add(new Row(r.line(), match, isin.isEmpty() ? null : isin, assetClass, cell(r, iName).isEmpty() ? null : cell(r, iName)));
        }
        return new InstrumentMaster(fileName, List.copyOf(rows));
    }

    private static String cell(Csv.Row r, int i) {
        return i < 0 ? "" : r.cells().get(i).trim();
    }

    public record Result(Ledger ledger, List<Diagnostic> diagnostics) {}

    /** Rewrites the instruments of all entries (and conversion targets); never changes anything but instrument data. */
    public Result apply(Ledger ledger) {
        List<Diagnostic> diags = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        Map<Instrument, Instrument> mapped = new LinkedHashMap<>();
        List<LedgerEntry> out = new ArrayList<>();
        for (LedgerEntry e : ledger.entries()) {
            out.add(e.mapInstruments(i -> mapped.computeIfAbsent(i, k -> correct(k, used, diags, e.source()))));
        }
        for (Row r : rows) {
            if (!used.contains(r.line())) {
                diags.add(Diagnostic.info("INSTRUMENT_MASTER_UNUSED", file + ":" + r.line() + ": no instrument in the ledger matches '" + r.match() + "'", null));
            }
        }
        return new Result(Ledger.of(out), diags);
    }

    private Instrument correct(Instrument ins, Set<Integer> used, List<Diagnostic> diags, SourceRef firstSeen) {
        for (Row r : rows) {
            boolean matches = r.match().equals(ins.isin()) || (ins.isin() == null && r.match().equalsIgnoreCase(ins.symbol()));
            if (!matches) continue;
            used.add(r.line());
            if (r.isin() != null && ins.isin() != null && !r.isin().equals(ins.isin())) {
                diags.add(Diagnostic.warning("INSTRUMENT_MASTER_CONFLICT", file + ":" + r.line() + " says " + r.match() + " has ISIN "
                        + r.isin() + " but the statement says " + ins.isin() + "; the master row is ignored", firstSeen));
                return ins;
            }
            Instrument fixed = new Instrument(ins.isin() != null ? ins.isin() : r.isin(), ins.symbol(),
                    r.name() != null ? r.name() : ins.name(), r.assetClass() != null ? r.assetClass() : ins.assetClass());
            if (!fixed.equals(ins)) {
                diags.add(Diagnostic.info("INSTRUMENT_MASTER_APPLIED", file + ":" + r.line() + " corrected " + ins.key() + " -> " + fixed.key()
                        + " (" + fixed.assetClass() + ")", firstSeen));
            }
            return fixed;
        }
        return ins;
    }
}
