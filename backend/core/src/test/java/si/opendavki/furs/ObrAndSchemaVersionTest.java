package si.opendavki.furs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import si.opendavki.model.InterestRow;
import si.opendavki.model.ObrReport;
import si.opendavki.model.Taxpayer;

class ObrAndSchemaVersionTest {
    private static final Path SCHEMAS = Path.of("../../schemas");
    private final SchemaValidator validator = new SchemaValidator(SCHEMAS);
    private final Taxpayer tp = new Taxpayer("12345678", "FO");

    private static InterestRow row(String date, String value, String tax, String country) {
        return new InterestRow(LocalDate.parse(date), "Some Broker Ltd", null, country, null, new BigDecimal(value), new BigDecimal(tax), country, "t");
    }

    /** The writers produce the layout of the NEWEST version of each form in schemas/; a newer file there means adapting them. */
    @Test void everyGeneratedFormUsesTheNewestVersionInTheSchemaCollection() throws IOException {
        for (FormType f : FormType.values()) {
            Matcher own = Pattern.compile("(.+)_(\\d+)\\.xsd").matcher(f.xsdFileName);
            assertTrue(own.matches(), f.xsdFileName);
            int newest = 0;
            try (Stream<Path> files = Files.list(SCHEMAS)) {
                Pattern p = Pattern.compile(Pattern.quote(own.group(1)) + "_(\\d+)\\.xsd");
                for (Path file : files.toList()) {
                    Matcher m = p.matcher(file.getFileName().toString());
                    if (m.matches()) newest = Math.max(newest, Integer.parseInt(m.group(1)));
                }
            }
            assertEquals(Integer.parseInt(own.group(2)), newest, "schemas/ holds a newer " + own.group(1) + " than the writers support: adopt it as described in docs/schemas.md");
        }
    }

    @Test void interestReportIsValidAndKeepsGrossAndForeignTaxSeparate() {
        String xml = new ObrXmlWriter().write(new ObrReport(tp, 2025, List.of(row("2025-02-03", "1.235", "0.18", "IE"), row("2025-03-03", "0.10", "0", "IE"))));
        var r = validator.validate(FormType.OBR, xml);
        assertTrue(r.valid(), r.issues() + "\n" + xml);
        assertTrue(xml.contains("<Value>1.24</Value>"), xml);          // 1.235 rounds HALF_UP to 1.24
        assertTrue(xml.contains("<ForeignTax>0.18</ForeignTax>"));
        assertTrue(xml.contains("<Period>2025</Period>"));
        assertTrue(xml.contains("<edp:bodyContent/>"), "Doh_Obr_2 requires bodyContent (Doh_Div must not have it)");
        assertEquals(2, xml.split("<Interest>", -1).length - 1);
    }

    @Test void optionalPayerDetailsAreOmittedNotInvented() {
        InterestRow bare = new InterestRow(LocalDate.parse("2025-02-03"), null, null, null, null, new BigDecimal("1.00"), null, null, "t");
        String xml = new ObrXmlWriter().write(new ObrReport(tp, 2025, List.of(bare)));
        assertTrue(validator.validate(FormType.OBR, xml).valid(), xml);
        for (String absent : List.of("<Name>", "<Address>", "<Country>", "<Country2>", "<Type>")) assertFalse(xml.contains(absent), absent);
    }

    @Test void emptyReportAndBadRowsAreHandled() {
        assertTrue(validator.validate(FormType.OBR, new ObrXmlWriter().write(new ObrReport(tp, 2025, List.of()))).valid());
        assertThrows(IllegalArgumentException.class, () -> new InterestRow(LocalDate.parse("2025-01-01"), null, null, null, null, new BigDecimal("-1"), null, null, "t"));
        assertThrows(IllegalArgumentException.class, () -> new InterestRow(LocalDate.parse("2025-01-01"), null, null, "Ireland", null, BigDecimal.ONE, null, null, "t"));
    }
}
