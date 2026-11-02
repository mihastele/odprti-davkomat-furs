package si.opendavki.broker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Summarises the layout of a CSV statement so it can be shared or compared with a profile <b>without its contents</b>:
 * column names, how values are written (digits shown as 9, capitals as A, lowercase as a) and, only for columns that are
 * categorical by their name (type, action, currency, ...) or named by the user, the distinct values. Everything else (names,
 * ISINs, ids, amounts, dates) appears only as a shape.
 *
 * <p>The output is meant to be safe to paste into an issue, but the user must still read it first: nothing can prove that an
 * unusual column does not hold something personal.
 */
public final class StatementDescriber {
    private static final List<String> CATEGORICAL_NAME_PARTS = List.of("type", "action", "currency", "ccy", "side", "status", "category",
            "direction", "venue", "exchange", "kind", "operation");
    private static final int MAX_VALUES = 20, MAX_VALUE_LENGTH = 40, MAX_SHAPES = 5;
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{9,}");

    private StatementDescriber() {}

    public static String describe(StatementSource source, Collection<String> extraValueColumns) {
        String text = source.text();
        char delimiter = guessDelimiter(text);
        List<Csv.Row> rows;
        try {
            rows = Csv.parse(text, delimiter);
        } catch (IllegalArgumentException e) {
            return "# Layout of " + source.name() + "\n\nThe file is not valid CSV: " + e.getMessage() + "\n";
        }
        StringBuilder md = new StringBuilder("# Layout of ").append(source.name()).append("\n\n")
                .append("Column names and value shapes only (digits = 9, capitals = A, lowercase = a). Distinct values are listed only for ")
                .append("categorical columns. **Read this before sharing it.**\n\n");
        if (rows.isEmpty()) return md.append("The file is empty.\n").toString();

        List<String> header = rows.get(0).cells().stream().map(String::trim).toList();
        List<Csv.Row> data = rows.subList(1, rows.size());
        long mismatched = data.stream().filter(r -> r.cells().size() != header.size()).count();
        md.append("- delimiter: `").append(delimiter == '\t' ? "\\t" : String.valueOf(delimiter)).append("`\n")
                .append("- data rows: ").append(data.size()).append(" (").append(mismatched).append(" with a different number of cells than the header)\n")
                .append("- columns: ").append(header.size()).append("\n")
                .append("- header: `").append(String.join(delimiter == '\t' ? "\t" : String.valueOf(delimiter), header)).append("`\n\n");

        Set<String> extra = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        extra.addAll(extraValueColumns);
        for (int c = 0; c < header.size(); c++) {
            final int col = c;
            List<String> values = data.stream().filter(r -> r.cells().size() == header.size()).map(r -> r.cells().get(col).trim()).toList();
            long empty = values.stream().filter(String::isEmpty).count();
            Map<String, Integer> shapes = new LinkedHashMap<>();
            values.stream().filter(v -> !v.isEmpty()).forEach(v -> shapes.merge(shape(v), 1, Integer::sum));
            List<String> top = shapes.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(MAX_SHAPES)
                    .map(e -> "`" + e.getKey() + "` x" + e.getValue()).toList();
            String name = header.get(c);
            md.append("### ").append(c + 1).append(". ").append(name.isEmpty() ? "(unnamed)" : "`" + name + "`").append("\n\n")
                    .append("- empty in ").append(empty).append(" of ").append(values.size()).append(" rows; distinct values: ")
                    .append(values.stream().filter(v -> !v.isEmpty()).distinct().count()).append("\n")
                    .append("- shapes: ").append(top.isEmpty() ? "-" : String.join(", ", top)).append(shapes.size() > MAX_SHAPES ? ", ..." : "").append("\n");
            List<String> distinct = values.stream().filter(v -> !v.isEmpty()).distinct().sorted().toList();
            if (showsValues(name, extra) && !distinct.isEmpty()) {
                if (distinct.size() <= MAX_VALUES && distinct.stream().allMatch(StatementDescriber::safeToShow)) {
                    md.append("- values: ").append(String.join(" | ", distinct.stream().map(v -> "`" + v + "`").toList())).append("\n");
                } else {
                    md.append("- values: not shown (more than ").append(MAX_VALUES).append(" distinct or some look like identifiers)\n");
                }
            }
            md.append('\n');
        }
        return md.toString();
    }

    private static boolean showsValues(String column, Set<String> extra) {
        String lower = column.toLowerCase(Locale.ROOT);
        return extra.contains(column) || CATEGORICAL_NAME_PARTS.stream().anyMatch(lower::contains);
    }

    private static boolean safeToShow(String v) {
        return v.length() <= MAX_VALUE_LENGTH && !v.contains("@") && !LONG_DIGITS.matcher(v).find();
    }

    static String shape(String value) {
        StringBuilder out = new StringBuilder();
        char last = 0;
        int run = 0;
        List<String> tokens = new ArrayList<>();
        for (char ch : value.toCharArray()) {
            char s = Character.isDigit(ch) ? '9' : Character.isUpperCase(ch) ? 'A' : Character.isLowerCase(ch) ? 'a' : ch;
            if (s == last && (s == '9' || s == 'A' || s == 'a')) {
                run++;
            } else {
                flush(tokens, last, run);
                last = s;
                run = 1;
            }
        }
        flush(tokens, last, run);
        tokens.forEach(out::append);
        return out.toString();
    }

    private static void flush(List<String> tokens, char c, int run) {
        if (run == 0) return;
        if ((c == '9' || c == 'A' || c == 'a') && run > 4) tokens.add(c + "{" + run + "}");
        else tokens.add(String.valueOf(c).repeat(run));
    }

    private static char guessDelimiter(String text) {
        String first = text.lines().findFirst().orElse("");
        int comma = (int) first.chars().filter(c -> c == ',').count(), semi = (int) first.chars().filter(c -> c == ';').count(),
                tab = (int) first.chars().filter(c -> c == '\t').count();
        return tab > comma && tab > semi ? '\t' : semi > comma ? ';' : ',';
    }
}
