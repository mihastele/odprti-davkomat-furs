package si.opendavki.cli;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import si.opendavki.furs.DivXmlWriter;
import si.opendavki.furs.FormType;
import si.opendavki.furs.KdvpXmlWriter;
import si.opendavki.furs.SchemaValidator;
import si.opendavki.fx.FxConfig;
import si.opendavki.fx.FxService;
import si.opendavki.json.Json;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.ledger.Ledger;
import si.opendavki.model.Taxpayer;
import si.opendavki.tax.AuditReport;
import si.opendavki.tax.LotMatch;
import si.opendavki.tax.TaxEngine;
import si.opendavki.tax.TaxOptions;
import si.opendavki.tax.TaxResult;

/**
 * Computes the declarations of a year and renders everything derived from them (XML, XSD validation, audit). Shared by the
 * command line and the local web server; prints nothing and writes nothing.
 */
final class ReportRunner {
    private ReportRunner() {}

    /** @param schemaAvailable false if the XSD was missing (then {@code message} says so); {@code issues} are XSD findings */
    record Validation(boolean schemaAvailable, boolean valid, List<String> issues, String message) {}

    /** Realised result of one instrument in EUR, rounded to cents for reading only; eDavki computes the declared figures. */
    record Gain(String instrument, String symbol, BigDecimal gainEur) {}

    /**
     * @param xmlWithheld true if the result is incomplete and the caller did not allow partial output: no XML is produced then
     */
    record Output(TaxResult result, boolean xmlWithheld, String kdvpXml, String divXml, Validation kdvpValidation, Validation divValidation,
                  String auditMarkdown, String auditJson, List<Gain> gains) {}

    static Output run(Ledger ledger, int year, String taxNumber, Map<String, String> env, Path schemaDir, boolean allowIncomplete) throws IOException {
        Taxpayer taxpayer;
        FxConfig fx;
        TaxOptions options;
        List<Diagnostic> corrections = new ArrayList<>();
        try {
            taxpayer = new Taxpayer(taxNumber, "FO");
            fx = FxConfig.from(env);
            options = TaxOptions.from(env);
            ledger = ImportRunner.applyCorrections(ledger, env, corrections);
        } catch (IllegalArgumentException e) {
            throw new InputException(e.getMessage());
        }
        TaxResult result = new TaxEngine(new FxService(fx), options).compute(ledger, year, taxpayer, corrections);

        String auditJson = Json.MAPPER.writeValueAsString(result.audit()) + "\n";
        String auditMarkdown = AuditReport.markdown(result.audit());
        List<Gain> gains = gains(result);
        boolean withheld = !result.complete() && !allowIncomplete;
        if (withheld) return new Output(result, true, null, null, null, null, auditMarkdown, auditJson, gains);

        String kdvp = result.kdvp() == null ? null : new KdvpXmlWriter().write(result.kdvp());
        String div = result.div() == null ? null : new DivXmlWriter().write(result.div());
        SchemaValidator validator = new SchemaValidator(schemaDir);
        return new Output(result, false, kdvp, div, kdvp == null ? null : validate(validator, FormType.KDVP, kdvp),
                div == null ? null : validate(validator, FormType.DIV, div), auditMarkdown, auditJson, gains);
    }

    private static Validation validate(SchemaValidator validator, FormType form, String xml) {
        try {
            SchemaValidator.Result r = validator.validate(form, xml);
            return new Validation(true, r.valid(), r.issues().stream().map(SchemaValidator.Issue::toString).toList(), null);
        } catch (SchemaValidator.SchemaMissingException e) {
            return new Validation(false, false, List.of(), e.getMessage());
        }
    }

    private static List<Gain> gains(TaxResult r) {
        Map<String, BigDecimal> sum = new TreeMap<>();
        Map<String, String> symbol = new TreeMap<>();
        for (LotMatch m : r.lotMatches()) {
            sum.merge(m.instrument(), m.gainEur(), BigDecimal::add);
            symbol.putIfAbsent(m.instrument(), m.symbol());
        }
        List<Gain> out = new ArrayList<>();
        sum.forEach((k, v) -> out.add(new Gain(k, symbol.get(k), v.setScale(2, RoundingMode.HALF_UP))));
        return out;
    }
}
