package si.opendavki.broker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the cell text of every sheet of an {@code .xlsx} workbook (a zip of XML parts) without a spreadsheet library.
 * Values are the stored text: shared and inline strings as text, numbers and dates as their raw stored number (dates are
 * serial numbers), so nothing is reformatted or guessed. Zip bombs are refused by size limits; DTDs are disabled.
 */
public final class XlsxSheets {
    private static final long MAX_PART_BYTES = 64L * 1024 * 1024, MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_ENTRIES = 2000;

    private XlsxSheets() {}

    public static boolean isXlsx(byte[] content) {
        return content.length > 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4;
    }

    /** Sheet name to rows, in workbook order. Short rows are padded with empty strings to the sheet's width. */
    public static Map<String, List<List<String>>> read(byte[] content) throws IOException {
        Map<String, byte[]> parts = unzip(content);
        List<String> shared = parts.containsKey("xl/sharedStrings.xml") ? sharedStrings(parts.get("xl/sharedStrings.xml")) : List.of();
        byte[] workbook = parts.get("xl/workbook.xml"), rels = parts.get("xl/_rels/workbook.xml.rels");
        if (workbook == null || rels == null) throw new IOException("not an Excel workbook (xl/workbook.xml missing)");
        Map<String, String> relTargets = relationshipTargets(rels);
        Map<String, List<List<String>>> sheets = new LinkedHashMap<>();
        for (Map.Entry<String, String> sheet : sheetNames(workbook).entrySet()) { // name -> relationship id
            String target = relTargets.get(sheet.getValue());
            if (target == null) continue;
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            byte[] xml = parts.get(path);
            if (xml != null) sheets.put(sheet.getKey(), rows(xml, shared));
        }
        return sheets;
    }

    private static Map<String, byte[]> unzip(byte[] content) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry e;
            int entries = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("workbook has too many parts");
                String name = e.getName();
                if (!name.startsWith("xl/")) continue;
                byte[] data = readLimited(zip, MAX_PART_BYTES);
                total += data.length;
                if (total > MAX_TOTAL_BYTES) throw new IOException("workbook is too large when unpacked");
                parts.put(name, data);
            }
        }
        return parts;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > limit) throw new IOException("workbook part is too large when unpacked");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    private static XMLStreamReader reader(byte[] xml) throws XMLStreamException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        return f.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static List<String> sharedStrings(byte[] xml) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            XMLStreamReader r = reader(xml);
            StringBuilder cur = null;
            boolean inT = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    if (r.getLocalName().equals("si")) cur = new StringBuilder();
                    else if (r.getLocalName().equals("t") && cur != null) inT = true;
                } else if (ev == XMLStreamConstants.CHARACTERS && inT) {
                    cur.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    if (r.getLocalName().equals("t")) inT = false;
                    else if (r.getLocalName().equals("si") && cur != null) { out.add(cur.toString()); cur = null; }
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("cannot read shared strings: " + e.getMessage(), e);
        }
        return out;
    }

    private static Map<String, String> relationshipTargets(byte[] xml) throws IOException {
        Map<String, String> out = new HashMap<>();
        try {
            XMLStreamReader r = reader(xml);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("Relationship")) {
                    out.put(r.getAttributeValue(null, "Id"), r.getAttributeValue(null, "Target"));
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("cannot read workbook relationships: " + e.getMessage(), e);
        }
        return out;
    }

    private static Map<String, String> sheetNames(byte[] xml) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            XMLStreamReader r = reader(xml);
            while (r.hasNext()) {
                if (r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("sheet")) {
                    String id = null;
                    for (int i = 0; i < r.getAttributeCount(); i++) if (r.getAttributeLocalName(i).equals("id")) id = r.getAttributeValue(i);
                    out.put(r.getAttributeValue(null, "name"), id);
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("cannot read the sheet list: " + e.getMessage(), e);
        }
        return out;
    }

    private static List<List<String>> rows(byte[] xml, List<String> shared) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader r = reader(xml);
            List<String> row = null;
            String type = null, ref = null;
            StringBuilder value = null;
            boolean inValue = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "row" -> row = new ArrayList<>();
                        case "c" -> { type = r.getAttributeValue(null, "t"); ref = r.getAttributeValue(null, "r"); value = new StringBuilder(); }
                        case "v", "t" -> inValue = value != null;
                        default -> { }
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS && inValue) {
                    value.append(r.getText());
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "v", "t" -> inValue = false;
                        case "c" -> {
                            if (row != null && value != null) {
                                int col = ref == null ? row.size() : columnIndex(ref);
                                while (row.size() < col) row.add("");
                                String text = value.toString();
                                if ("s".equals(type)) text = shared.get(Integer.parseInt(text.trim()));
                                row.add(text);
                            }
                            value = null;
                        }
                        case "row" -> { if (row != null) rows.add(row); row = null; }
                        default -> { }
                    }
                }
            }
        } catch (XMLStreamException | RuntimeException e) {
            throw new IOException("cannot read a sheet: " + e.getMessage(), e);
        }
        int width = rows.stream().mapToInt(List::size).max().orElse(0);
        rows.forEach(r -> { while (r.size() < width) r.add(""); });
        return rows;
    }

    /** "C5" is column index 2. */
    static int columnIndex(String ref) {
        int col = 0;
        for (char c : ref.toCharArray()) {
            if (!Character.isLetter(c)) break;
            col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
        }
        return col - 1;
    }

    /** One sheet as CSV text (comma, quotes where needed) so the CSV tooling can describe it. */
    public static String toCsv(List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) out.append(',');
                String v = row.get(i);
                out.append(v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
            }
            out.append('\n');
        }
        return out.toString();
    }
}
