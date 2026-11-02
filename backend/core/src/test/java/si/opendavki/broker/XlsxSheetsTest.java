package si.opendavki.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** The workbook is built by hand (a zip of the XML parts Excel writes), so the expected cell values are known exactly. */
class XlsxSheetsTest {
    private static byte[] workbook(Map<String, String> parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> p : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(p.getKey()));
                zip.write(p.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static final String NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    private static final Map<String, String> BASE = new java.util.LinkedHashMap<>(Map.of(
            "xl/workbook.xml", "<workbook " + NS + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
                    + "<sheet name=\"Closed Positions\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Dividends\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Target=\"worksheets/sheet2.xml\"/></Relationships>",
            "xl/sharedStrings.xml", "<sst " + NS + "><si><t>Action</t></si><si><t>Units</t></si><si><r><t>Buy </t></r><r><t>AAPL</t></r></si></sst>",
            "xl/worksheets/sheet1.xml", "<worksheet " + NS + "><sheetData>"
                    + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c><c r=\"D1\" t=\"inlineStr\"><is><t>Date, time</t></is></c></row>"
                    + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>2.5</v></c><c r=\"D2\"><v>45000.5</v></c></row></sheetData></worksheet>",
            "xl/worksheets/sheet2.xml", "<worksheet " + NS + "><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>ISIN</t></is></c></row></sheetData></worksheet>"));

    @Test void readsSharedAndInlineStringsNumbersAndGaps() throws IOException {
        Map<String, List<List<String>>> sheets = XlsxSheets.read(workbook(BASE));
        assertEquals(List.of("Closed Positions", "Dividends"), List.copyOf(sheets.keySet()));
        assertEquals(List.of("Action", "Units", "", "Date, time"), sheets.get("Closed Positions").get(0));   // column C is empty and kept
        assertEquals(List.of("Buy AAPL", "2.5", "", "45000.5"), sheets.get("Closed Positions").get(1));      // rich text runs joined, raw numbers
        assertEquals(List.of(List.of("ISIN")), sheets.get("Dividends"));
    }

    @Test void convertsToCsvWithQuotingSoTheCsvToolsCanDescribeIt() throws IOException {
        String csv = XlsxSheets.toCsv(XlsxSheets.read(workbook(BASE)).get("Closed Positions"));
        assertEquals("Action,Units,,\"Date, time\"\nBuy AAPL,2.5,,45000.5\n", csv);
        String described = StatementDescriber.describe(StatementSource.ofText("w.xlsx", csv), List.of());
        assertTrue(described.contains("- columns: 4"), described);
        assertTrue(described.contains("- shapes: `9.9` x1"), described);        // the Units column: only its shape
        assertFalse(described.contains("2.5"), described);
        assertFalse(described.contains("45000.5"), described);                  // a non-categorical column's value never appears
        assertTrue(described.contains("values: `Buy AAPL`"), described);        // the Action column is categorical by name
    }

    @Test void detectsWorkbooksByTheirZipSignatureAndRejectsOtherZips() throws IOException {
        assertTrue(XlsxSheets.isXlsx(workbook(BASE)));
        assertFalse(XlsxSheets.isXlsx("a,b\n".getBytes()));
        assertThrows(IOException.class, () -> XlsxSheets.read(workbook(Map.of("readme.txt", "x"))));
        assertThrows(IOException.class, () -> XlsxSheets.read("PK\u0003\u0004 garbage".getBytes()));
    }

    @Test void columnReferencesAreConverted() {
        assertEquals(0, XlsxSheets.columnIndex("A1"));
        assertEquals(2, XlsxSheets.columnIndex("C5"));
        assertEquals(26, XlsxSheets.columnIndex("AA10"));
        assertEquals(27, XlsxSheets.columnIndex("AB2"));
    }

    @Test void externalEntitiesInTheXmlAreNotResolved() throws IOException {
        Map<String, String> evil = new java.util.LinkedHashMap<>(BASE);
        evil.put("xl/sharedStrings.xml", "<!DOCTYPE sst [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><sst " + NS + "><si><t>&x;</t></si></sst>");
        // reading must not expose the file: it either fails or yields no file content
        try {
            String all = XlsxSheets.read(workbook(evil)).toString();
            assertFalse(all.contains("root:"), all);
        } catch (IOException expected) {
            // refused is fine too
        }
    }
}
