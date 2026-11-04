package si.opendavki.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.StatementSource;
import si.opendavki.ledger.Ledger;

/** Public entry points of the package-private runners, for the web package. */
public final class Services {
    private Services() {}

    public static ImportOutcome runImport(List<StatementSource> sources, String adapterId, List<BrokerAdapter> extra, Map<String, String> env) throws IOException {
        ImportRunner.Outcome o = ImportRunner.run(sources, adapterId, extra, env);
        return new ImportOutcome(o.ledger(), o.files(), o.diagnostics(), o.hasErrors());
    }

    public static ReportOutcome runReport(Ledger ledger, int year, String taxNumber, Map<String, String> env, Path schemaDir, boolean allowIncomplete) throws IOException {
        ReportRunner.Output o = ReportRunner.run(ledger, year, taxNumber, env, schemaDir, allowIncomplete);
        return new ReportOutcome(o.result(), o.xmlWithheld(), o.kdvpXml(), o.divXml(), o.obrXml(), o.kdvpValidation(), o.divValidation(), o.obrValidation(),
                o.auditMarkdown(), o.auditJson(), o.gains());
    }

    public static String describe(StatementSource src, List<String> keep) throws IOException {
        return DescribeRunner.describe(src, keep);
    }

    public record ImportOutcome(Ledger ledger, List<ImportRunner.FileSummary> files, List<si.opendavki.ledger.Diagnostic> diagnostics, boolean hasErrors) {}

    public record ReportOutcome(si.opendavki.tax.TaxResult result, boolean xmlWithheld, String kdvpXml, String divXml, String obrXml,
                                ReportRunner.Validation kdvpValidation, ReportRunner.Validation divValidation, ReportRunner.Validation obrValidation,
                                String auditMarkdown, String auditJson, List<ReportRunner.Gain> gains) {}
}
