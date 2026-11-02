package si.opendavki.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import si.opendavki.broker.StatementSource;
import si.opendavki.ledger.Diagnostic;

/** The shared import/report logic, on a synthetic statement whose results were worked out by hand (see examples/). */
class RunnersTest {
    private static final Path SCHEMAS = Path.of("../../schemas");
    private static final String HEADER = "type,date,broker,account,id,isin,symbol,name,asset_class,quantity,price,currency,fee,fee_currency\n";

    private static StatementSource stmt(String name, String... rows) {
        return StatementSource.ofText(name, HEADER + String.join("\n", rows) + "\n");
    }

    private static Map<String, String> eurOnly() { return Map.of(); }

    @Test void importDetectsTheAdapterAndMergesStatements() throws IOException {
        ImportRunner.Outcome o = ImportRunner.run(List.of(
                stmt("a.csv", "BUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,10,100,EUR,,"),
                stmt("b.csv", "SELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR,,")), null, List.of(), eurOnly());
        assertEquals(2, o.ledger().entries().size());
        assertEquals(List.of("generic", "generic"), o.files().stream().map(ImportRunner.FileSummary::adapter).toList());
        assertEquals(1, o.files().get(0).rowsMapped());
        assertEquals("2023-01-02", o.files().get(0).first().toString());
        assertFalse(o.hasErrors(), o.diagnostics().toString());
    }

    @Test void importReportsStatementGapsAndLedgerErrors() throws IOException {
        ImportRunner.Outcome gap = ImportRunner.run(List.of(
                stmt("a.csv", "BUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,10,100,EUR,,"),
                stmt("b.csv", "BUY,2023-06-02,b,x,,US0378331005,AAPL,Apple,STOCK,1,100,EUR,,")), null, List.of(), eurOnly());
        assertTrue(gap.diagnostics().stream().anyMatch(d -> d.code().equals("STATEMENT_GAP")));
        ImportRunner.Outcome noHistory = ImportRunner.run(List.of(stmt("a.csv", "SELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR,,")), null, List.of(), eurOnly());
        assertTrue(noHistory.hasErrors());
    }

    @Test void importRejectsWhatItCannotRecogniseOrConfigure() {
        assertThrows(InputException.class, () -> ImportRunner.run(List.of(StatementSource.ofText("x.csv", "foo,bar\n1,2\n")), null, List.of(), eurOnly()));
        assertThrows(InputException.class, () -> ImportRunner.run(List.of(stmt("a.csv")), "nope", List.of(), eurOnly()));
        assertThrows(InputException.class, () -> ImportRunner.run(List.of(stmt("a.csv")), null, List.of(), Map.of("OPENDAVKI_TIMEZONE", "Mars/Base")));
    }

    @Test void importAppliesTheCorrectionFilesAndSaysSo(@TempDir Path dir) throws IOException {
        Path master = dir.resolve("m.csv");
        Files.writeString(master, "match,isin\nCUSIPX,IE00BFMXXD54\n");
        ImportRunner.Outcome o = ImportRunner.run(List.of(stmt("a.csv", "BUY,2023-01-02,b,x,,,CUSIPX,Mystery,STOCK,1,10,EUR,,")), null, List.of(),
                Map.of("OPENDAVKI_INSTRUMENTS_FILE", master.toString()));
        assertEquals("IE00BFMXXD54", o.ledger().entries().get(0).instrument().isin());
        assertTrue(o.diagnostics().stream().anyMatch(d -> d.code().equals("INSTRUMENT_MASTER_APPLIED")));
        assertTrue(o.diagnostics().stream().noneMatch(d -> d.code().equals("MISSING_ISIN")));
    }

    /** Buy 10 @100, sell 4 @130 (EUR): gain 4 x 30 = 120. */
    @Test void reportComputesValidatesAndRendersEverything() throws IOException {
        ImportRunner.Outcome o = ImportRunner.run(List.of(stmt("a.csv",
                "BUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,10,100,EUR,,", "SELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR,,")), null, List.of(), eurOnly());
        ReportRunner.Output r = ReportRunner.run(o.ledger(), 2024, "12345678", eurOnly(), SCHEMAS, false);
        assertTrue(r.result().complete(), r.result().diagnostics().toString());
        assertFalse(r.xmlWithheld());
        assertTrue(r.kdvpValidation().valid(), r.kdvpValidation().toString());
        assertNull(r.divXml());
        assertTrue(r.kdvpXml().contains("<ISIN>US0378331005</ISIN>"));
        assertEquals(1, r.gains().size());
        assertEquals(0, new BigDecimal("120.00").compareTo(r.gains().get(0).gainEur()));
        assertTrue(r.auditMarkdown().contains("# Audit report 2024"));
        assertTrue(r.auditJson().contains("\"lotMatches\""));
    }

    @Test void reportWithholdsXmlForAnIncompleteResultUnlessAllowed() throws IOException {
        ImportRunner.Outcome o = ImportRunner.run(List.of(stmt("a.csv", "SELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR,,")), null, List.of(), eurOnly());
        ReportRunner.Output strict = ReportRunner.run(o.ledger(), 2024, "12345678", eurOnly(), SCHEMAS, false);
        assertTrue(strict.xmlWithheld());
        assertNull(strict.kdvpXml());
        assertFalse(strict.result().complete());
        assertTrue(strict.auditMarkdown().contains("INCOMPLETE")); // the audit is still produced: it explains what is missing
        ReportRunner.Output partial = ReportRunner.run(o.ledger(), 2024, "12345678", eurOnly(), SCHEMAS, true);
        assertFalse(partial.xmlWithheld());
    }

    @Test void reportReportsAMissingSchemaInsteadOfFailing(@TempDir Path emptyDir) throws IOException {
        ImportRunner.Outcome o = ImportRunner.run(List.of(stmt("a.csv",
                "BUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,10,100,EUR,,", "SELL,2024-02-01,b,x,,US0378331005,AAPL,Apple,STOCK,4,130,EUR,,")), null, List.of(), eurOnly());
        ReportRunner.Output r = ReportRunner.run(o.ledger(), 2024, "12345678", eurOnly(), emptyDir, false);
        assertFalse(r.kdvpValidation().schemaAvailable());
        assertTrue(r.kdvpValidation().message().contains("Doh_KDVP_9.xsd"));
    }

    @Test void reportRejectsBadInput() throws IOException {
        ImportRunner.Outcome o = ImportRunner.run(List.of(stmt("a.csv", "BUY,2023-01-02,b,x,,US0378331005,AAPL,Apple,STOCK,10,100,EUR,,")), null, List.of(), eurOnly());
        assertThrows(InputException.class, () -> ReportRunner.run(o.ledger(), 2024, "123", eurOnly(), SCHEMAS, false));
        assertThrows(InputException.class, () -> ReportRunner.run(o.ledger(), 2024, "12345678", Map.of("OPENDAVKI_FX_SOURCE", "XE"), SCHEMAS, false));
    }

    @Test void theCommandLineSaysFileNotFoundInsteadOfPrintingAStackTrace() {
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        java.io.PrintStream old = System.err;
        System.setErr(new java.io.PrintStream(err));
        try {
            int exit = Main.commandLine().execute("describe", "/nonexistent/statement.csv");
            assertEquals(2, exit);
        } finally {
            System.setErr(old);
        }
        assertTrue(err.toString().contains("File not found: /nonexistent/statement.csv"), err.toString());
        assertFalse(err.toString().contains("at java."), err.toString());
    }
}
