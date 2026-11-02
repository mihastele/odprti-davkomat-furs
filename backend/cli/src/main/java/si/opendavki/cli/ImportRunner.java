package si.opendavki.cli;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import si.opendavki.broker.AdapterContext;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.BrokerAdapters;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.ledger.Diagnostic;
import si.opendavki.ledger.InstrumentMaster;
import si.opendavki.ledger.Ledger;
import si.opendavki.ledger.LedgerChecker;
import si.opendavki.ledger.LedgerEntry;
import si.opendavki.ledger.LedgerOverrides;
import si.opendavki.ledger.StatementCoverage;

/**
 * Reads statements into one checked ledger. Shared by the command line and the local web server so both behave identically;
 * it prints nothing and writes nothing.
 */
final class ImportRunner {
    private ImportRunner() {}

    record FileSummary(String file, String adapter, int rowsRead, int rowsMapped, int rowsSkipped, int rowsFailed, LocalDate first, LocalDate last) {}

    record Outcome(Ledger ledger, List<FileSummary> files, List<Diagnostic> diagnostics) {
        boolean hasErrors() {
            return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        }
    }

    /**
     * @param adapterId null to detect the adapter of each file
     * @throws InputException if no adapter fits, the adapter is unknown or a settings/correction file is invalid
     */
    static Outcome run(List<StatementSource> sources, String adapterId, List<BrokerAdapter> extraAdapters, Map<String, String> env) throws IOException {
        AdapterContext context;
        try {
            context = AdapterContext.from(env);
        } catch (IllegalArgumentException e) {
            throw new InputException("Configuration error: " + e.getMessage());
        }
        List<BrokerAdapter> candidates = new ArrayList<>(BrokerAdapters.all());
        candidates.addAll(extraAdapters);

        List<LedgerEntry> all = new ArrayList<>();
        List<Diagnostic> diags = new ArrayList<>();
        List<FileSummary> files = new ArrayList<>();
        List<StatementCoverage.Coverage> coverage = new ArrayList<>();
        for (StatementSource src : sources) {
            BrokerAdapter adapter = choose(src, adapterId, candidates);
            ImportResult r = adapter.read(src, context);
            StatementCoverage.Coverage cov = StatementCoverage.Coverage.of(src.name(), adapter.id(), r.entries());
            files.add(new FileSummary(src.name(), adapter.id(), r.stats().rowsRead(), r.stats().rowsMapped(), r.stats().rowsSkipped(),
                    r.stats().rowsFailed(), cov == null ? null : cov.first(), cov == null ? null : cov.last()));
            if (cov != null) coverage.add(cov);
            all.addAll(r.entries());
            diags.addAll(r.diagnostics());
        }
        Ledger.MergeResult merged = Ledger.merge(all);
        diags.addAll(merged.diagnostics());
        Ledger ledger;
        try {
            ledger = applyCorrections(merged.ledger(), env, diags);
        } catch (IllegalArgumentException e) {
            throw new InputException("Correction file error: " + e.getMessage());
        }
        diags.addAll(StatementCoverage.gaps(coverage));
        diags.addAll(LedgerChecker.check(ledger));
        return new Outcome(ledger, List.copyOf(files), List.copyOf(diags));
    }

    private static BrokerAdapter choose(StatementSource src, String adapterId, List<BrokerAdapter> candidates) {
        if (adapterId != null) {
            return candidates.stream().filter(a -> a.id().equals(adapterId)).findFirst()
                    .orElseThrow(() -> new InputException("Unknown adapter '" + adapterId + "'. See `adapters`."));
        }
        List<BrokerAdapter> matching = candidates.stream().filter(a -> a.accepts(src)).toList();
        if (matching.size() > 1) {
            throw new InputException(src.name() + " matches several adapters " + matching.stream().map(BrokerAdapter::id).toList() + "; choose one explicitly");
        }
        if (matching.isEmpty()) {
            throw new InputException("No adapter recognises " + src.name() + "; run `describe` on it, or choose an adapter / profile.");
        }
        return matching.get(0);
    }

    /**
     * Applies the user's correction files: ignored entries first (so a master row cannot resurrect them), then the
     * instrument master. Both are idempotent, so `report` can apply them again to a ledger written by `import`.
     */
    static Ledger applyCorrections(Ledger ledger, Map<String, String> env, List<Diagnostic> diags) throws IOException {
        java.util.Optional<LedgerOverrides> overrides = LedgerOverrides.fromEnvironment(env);
        if (overrides.isPresent()) {
            LedgerOverrides.Result r = overrides.get().apply(ledger);
            ledger = r.ledger();
            diags.addAll(r.diagnostics());
        }
        java.util.Optional<InstrumentMaster> master = InstrumentMaster.fromEnvironment(env);
        if (master.isPresent()) {
            InstrumentMaster.Result r = master.get().apply(ledger);
            ledger = r.ledger();
            diags.addAll(r.diagnostics());
        }
        return ledger;
    }
}
