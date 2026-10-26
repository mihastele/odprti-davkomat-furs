package si.opendavki.broker;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 CSV reader (quoted fields, escaped quotes, embedded newlines) for adapter use. */
public final class Csv {
    private Csv() {}

    /** @param line 1-based line of the physical file where the record starts (for audit references) */
    public record Row(int line, List<String> cells) {}

    public static List<Row> parse(String text, char delimiter) {
        List<Row> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, any = false;
        int line = 1, startLine = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else {
                    if (c == '\n') line++;
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true; any = true;
            } else if (c == delimiter) {
                cells.add(cur.toString()); cur.setLength(0); any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                if (any || cur.length() > 0) {
                    cells.add(cur.toString());
                    rows.add(new Row(startLine, cells));
                    cells = new ArrayList<>();
                }
                cur.setLength(0); any = false;
                line++; startLine = line;
            } else {
                cur.append(c); any = true;
            }
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted field starting at line " + startLine);
        if (any || cur.length() > 0) {
            cells.add(cur.toString());
            rows.add(new Row(startLine, cells));
        }
        return rows;
    }
}
