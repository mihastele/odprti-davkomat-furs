package si.opendavki.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import si.opendavki.fx.FxService;
import si.opendavki.fx.Rate;
import si.opendavki.fx.RateUnavailableException;
import si.opendavki.ledger.*;
import si.opendavki.model.*;

/**
 * Turns a ledger into the Doh-KDVP and Doh-Div inputs for one tax year. See docs/tax-engine.md for the rules.
 *
 * <p>Policy: nothing is guessed. An instrument that hits an error (missing purchase history, unsupported corporate
 * action, missing rate, unknown cost basis, ...) is left out of the report entirely and the result is marked incomplete.
 * Only instruments with a sale in the year are processed, so old data of unrelated holdings cannot cause errors.
 */
public final class TaxEngine {
    private static final int SCALE = 12;

    private final FxService fx;
    private final TaxOptions options;

    public TaxEngine(FxService fx, TaxOptions options) {
        this.fx = fx;
        this.options = options;
    }

    public TaxResult compute(Ledger ledger, int year, Taxpayer taxpayer) {
        Run run = new Run(year);
        LocalDate yearStart = LocalDate.of(year, 1, 1), yearEnd = LocalDate.of(year, 12, 31);

        Map<String, List<LedgerEntry>> byInstrument = new LinkedHashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            if (!e.date().isAfter(yearEnd)) byInstrument.computeIfAbsent(e.instrument().key(), k -> new ArrayList<>()).add(e);
        }

        List<KdvpItem> items = new ArrayList<>();
        for (List<LedgerEntry> entries : byInstrument.values()) {
            boolean soldInYear = entries.stream().anyMatch(e -> e instanceof Trade t && t.side() == Trade.Side.SELL
                    && !t.date().isBefore(yearStart));
            if (soldInYear) {
                new InstrumentRun(run, entries, yearStart).process().ifPresent(items::add);
            }
        }
        List<Dividend> dividends = dividends(run, ledger, yearStart, yearEnd);

        boolean complete = run.diagnostics.stream().noneMatch(d -> d.severity() == Diagnostic.Severity.ERROR);
        KdvpReport kdvp = items.isEmpty() ? null : new KdvpReport(taxpayer, year, true, items);
        DivReport div = dividends.isEmpty() ? null : new DivReport(taxpayer, year, dividends);
        return new TaxResult(year, kdvp, div, List.copyOf(run.diagnostics), List.copyOf(run.matches), List.copyOf(run.rates), complete, options.includeFees());
    }

    /** Shared mutable collectors for one computation. */
    private static final class Run {
        final int year;
        final List<Diagnostic> diagnostics = new ArrayList<>();
        final List<LotMatch> matches = new ArrayList<>();
        final List<RateUse> rates = new ArrayList<>();
        Run(int year) { this.year = year; }
    }

    /** Signals the first problem of an instrument; the instrument is then excluded. */
    private static final class Failure extends RuntimeException {
        final Diagnostic diagnostic;
        Failure(String code, String message, SourceRef source) {
            super(message);
            this.diagnostic = Diagnostic.error(code, message, source);
        }
    }

    // ---- EUR conversion ---------------------------------------------------------------------------------------

    private BigDecimal eur(Run run, BigDecimal amount, String currency, LocalDate date, LedgerEntry e, String what) {
        Rate r;
        try {
            r = fx.rate(currency, date);
        } catch (RateUnavailableException ex) {
            throw new Failure("RATE_UNAVAILABLE", ex.getMessage(), e.source());
        }
        if (!currency.equals("EUR")) {
            run.rates.add(new RateUse(e.id(), e.source(), what, currency, r.requestedDate(), r.rateDate(), r.unitsPerEur(), r.source()));
            if (r.isFallback()) {
                run.diagnostics.add(Diagnostic.info("RATE_FALLBACK", "No " + r.source() + " rate for " + currency + " on " + date
                        + "; used the rate of " + r.rateDate(), e.source()));
            }
        }
        return r.toEur(amount);
    }

    /** Unit price in EUR, including the fee share when configured (added for purchases, deducted for sales). */
    private BigDecimal unitEur(Run run, Trade t) {
        BigDecimal unit = eur(run, t.price(), t.currency(), t.date(), t, "price");
        if (options.includeFees() && t.fee().signum() > 0) {
            BigDecimal feePerUnit = eur(run, t.fee(), t.feeCurrency(), t.date(), t, "fee").divide(t.quantity(), SCALE, RoundingMode.HALF_UP);
            unit = t.side() == Trade.Side.BUY ? unit.add(feePerUnit) : unit.subtract(feePerUnit);
        }
        if (unit.signum() < 0) {
            throw new Failure("NEGATIVE_UNIT_VALUE", "Unit value after fees is negative (" + unit.toPlainString() + " EUR)", t.source());
        }
        return unit;
    }

    // ---- per-instrument FIFO ----------------------------------------------------------------------------------

    private record Split(LocalDate date, BigDecimal newQty, BigDecimal oldQty) {}

    private static final class Lot {
        final LocalDate date;
        BigDecimal qty;
        BigDecimal unitCost; // null: cost unknown (incoming transfer without basis)
        final SourceRef source;
        final String entryId;
        final long seq;
        Lot(LocalDate date, BigDecimal qty, BigDecimal unitCost, SourceRef source, String entryId, long seq) {
            this.date = date; this.qty = qty; this.unitCost = unitCost; this.source = source; this.entryId = entryId; this.seq = seq;
        }
    }

    private record Bought(Trade trade, BigDecimal unitCost) {}
    private record Sold(Trade trade, BigDecimal unitProceeds) {}
    /** A lot that entered the pool in the report year through an incoming transfer that carried its own date and cost. */
    private record Arrived(LocalDate acquired, LocalDate arrivedOn, BigDecimal qty, BigDecimal unitCost, SourceRef source) {}

    private final class InstrumentRun {
        private final Run run;
        private final List<LedgerEntry> entries;
        private final LocalDate yearStart;
        private final Instrument instrument;
        private final List<Lot> pool = new ArrayList<>();
        private final List<LotMatch> matches = new ArrayList<>();
        private final List<Bought> boughtInYear = new ArrayList<>();
        private final List<Sold> soldInYear = new ArrayList<>();
        private final List<Arrived> arrivedInYear = new ArrayList<>();
        private final List<Split> splitsInYear = new ArrayList<>();
        private final Set<String> appliedSplits = new HashSet<>();
        private final Set<String> pairedTransfers;
        private List<Lot> snapshot;
        private long seq;

        InstrumentRun(Run run, List<LedgerEntry> entries, LocalDate yearStart) {
            this.run = run;
            this.entries = entries;
            this.yearStart = yearStart;
            this.instrument = entries.get(0).instrument();
            this.pairedTransfers = TransferPairing.paired(entries);
        }

        java.util.Optional<KdvpItem> process() {
            int rateStart = run.rates.size();
            try {
                requireIsin();
                for (LedgerEntry e : entries) {
                    if (snapshot == null && !e.date().isBefore(yearStart)) snapshot = copyPool();
                    apply(e);
                }
                if (snapshot == null) snapshot = copyPool();
                KdvpItem item = buildSheet();
                run.matches.addAll(matches);
                return java.util.Optional.of(item);
            } catch (Failure f) {
                // keep this instrument's INFO/WARNING trail but drop its rates and matches: nothing of it is reported
                run.diagnostics.add(f.diagnostic);
                while (run.rates.size() > rateStart) run.rates.remove(run.rates.size() - 1);
                run.diagnostics.add(Diagnostic.error("INSTRUMENT_EXCLUDED", instrument.key()
                        + " is left out of the Doh-KDVP because of the error above", f.diagnostic.source()));
                return java.util.Optional.empty();
            }
        }

        private void requireIsin() {
            SourceRef ref = entries.get(0).source();
            if (instrument.isin() == null) {
                throw new Failure("MISSING_ISIN", "Sold instrument " + instrument.symbol() + " has no ISIN; Doh-KDVP needs it", ref);
            }
            if (!instrument.isin().matches("[A-Z]{2}[A-Z0-9]{9}\\d")) {
                throw new Failure("INVALID_ISIN", "Invalid ISIN '" + instrument.isin() + "'", ref);
            }
        }

        private boolean inYear(LocalDate d) { return !d.isBefore(yearStart); }

        private void apply(LedgerEntry e) {
            switch (e) {
                case Trade t when t.side() == Trade.Side.BUY -> buy(t);
                case Trade t -> sell(t);
                case CorporateAction c -> corporateAction(c);
                case SecurityTransfer s -> transfer(s);
                case DividendPayment d -> { /* dividends are handled separately */ }
            }
        }

        private void buy(Trade t) {
            BigDecimal unitCost = unitEur(run, t);
            addLot(new Lot(t.date(), t.quantity(), unitCost, t.source(), t.id(), seq++));
            if (inYear(t.date())) boughtInYear.add(new Bought(t, unitCost));
        }

        private void sell(Trade t) {
            BigDecimal unitProceeds = unitEur(run, t);
            BigDecimal need = t.quantity();
            for (Lot lot : new ArrayList<>(pool)) {
                if (need.signum() == 0) break;
                if (lot.unitCost == null) {
                    throw new Failure("SALE_USES_UNKNOWN_COST", "Sale of " + instrument.key() + " on " + t.date()
                            + " consumes a lot with unknown cost (transfer from " + lot.source + " without cost basis)", t.source());
                }
                BigDecimal take = lot.qty.min(need);
                BigDecimal gain = take.multiply(unitProceeds.subtract(lot.unitCost));
                if (inYear(t.date())) {
                    matches.add(new LotMatch(instrument.key(), instrument.symbol(), t.id(), t.source(), t.date(),
                            lot.entryId, lot.source, lot.date, take, lot.unitCost, unitProceeds, gain));
                }
                lot.qty = lot.qty.subtract(take);
                need = need.subtract(take);
                if (lot.qty.signum() == 0) pool.remove(lot);
            }
            if (need.signum() > 0) {
                throw new Failure("MISSING_PURCHASE_HISTORY", "Sale of " + t.quantity().stripTrailingZeros().toPlainString() + " "
                        + instrument.key() + " on " + t.date() + " is short by " + need.stripTrailingZeros().toPlainString()
                        + ": a purchase or transfer-in is missing", t.source());
            }
            if (inYear(t.date())) soldInYear.add(new Sold(t, unitProceeds));
        }

        private void corporateAction(CorporateAction c) {
            if (c.type() != CorporateAction.Type.SPLIT) {
                throw new Failure("UNSUPPORTED_CORPORATE_ACTION", "Corporate action on " + instrument.key() + " (" + c.description()
                        + ") is not supported; positions and cost basis cannot be derived", c.source());
            }
            // The same split is reported by every broker holding the instrument; apply it once.
            String key = c.date() + "|" + c.newQuantity().stripTrailingZeros() + "|" + c.oldQuantity().stripTrailingZeros();
            if (!appliedSplits.add(key)) {
                run.diagnostics.add(Diagnostic.info("SPLIT_DUPLICATE", "Split of " + instrument.key() + " on " + c.date()
                        + " already applied from another entry", c.source()));
                return;
            }
            for (Lot lot : pool) {
                lot.qty = lot.qty.multiply(c.newQuantity()).divide(c.oldQuantity(), SCALE, RoundingMode.HALF_UP);
                if (lot.unitCost != null) lot.unitCost = lot.unitCost.multiply(c.oldQuantity()).divide(c.newQuantity(), SCALE, RoundingMode.HALF_UP);
            }
            if (inYear(c.date())) splitsInYear.add(new Split(c.date(), c.newQuantity(), c.oldQuantity()));
        }

        private void transfer(SecurityTransfer s) {
            if (pairedTransfers.contains(s.id())) {
                run.diagnostics.add(Diagnostic.info("TRANSFER_PAIRED", "Transfer of " + s.quantity().stripTrailingZeros().toPlainString()
                        + " " + instrument.key() + " between own accounts; lots keep their original date and cost", s.source()));
                return;
            }
            if (s.direction() == SecurityTransfer.Direction.IN) {
                BigDecimal cost = null;
                LocalDate date = s.acquisitionDate() != null ? s.acquisitionDate() : s.date();
                if (s.costBasisPerUnit() != null && s.acquisitionDate() != null) {
                    cost = eur(run, s.costBasisPerUnit(), s.costCurrency(), s.acquisitionDate(), s, "transfer cost basis");
                } else {
                    run.diagnostics.add(Diagnostic.warning("TRANSFER_BASIS_MISSING", "Incoming transfer of " + instrument.key()
                            + " has no acquisition date and cost basis; selling these shares will fail", s.source()));
                }
                Lot lot = new Lot(date, s.quantity(), cost, s.source(), s.id(), seq++);
                addLot(lot);
                if (inYear(s.date()) && cost != null) arrivedInYear.add(new Arrived(date, s.date(), s.quantity(), cost, s.source()));
            } else {
                run.diagnostics.add(Diagnostic.warning("TRANSFER_OUT_UNPAIRED", "Outgoing transfer of " + instrument.key()
                        + " has no matching incoming transfer; treated as leaving the portfolio (oldest lots first), not as a sale", s.source()));
                BigDecimal need = s.quantity();
                for (Lot lot : new ArrayList<>(pool)) {
                    if (need.signum() == 0) break;
                    BigDecimal take = lot.qty.min(need);
                    lot.qty = lot.qty.subtract(take);
                    need = need.subtract(take);
                    if (lot.qty.signum() == 0) pool.remove(lot);
                }
                if (need.signum() > 0) {
                    throw new Failure("MISSING_PURCHASE_HISTORY", "Transfer out of " + instrument.key() + " exceeds holdings by "
                            + need.stripTrailingZeros().toPlainString(), s.source());
                }
            }
        }

        /** Keeps the pool in FIFO order: acquisition date, then arrival. */
        private void addLot(Lot lot) {
            int i = 0;
            while (i < pool.size() && !pool.get(i).date.isAfter(lot.date)) i++;
            pool.add(i, lot);
        }

        private List<Lot> copyPool() {
            List<Lot> copy = new ArrayList<>();
            for (Lot l : pool) copy.add(new Lot(l.date, l.qty, l.unitCost, l.source, l.entryId, l.seq));
            return copy;
        }

        // ---- sheet ----------------------------------------------------------------------------------------

        /**
         * One PLVP sheet: lots still open on 1 January (remaining quantity, original date and unit cost), then the
         * year's purchases and sales, all restated into the units valid after the year's splits so the running stock (F8)
         * is consistent.
         */
        private KdvpItem buildSheet() {
            List<SecurityEvent> events = new ArrayList<>();
            for (Lot l : snapshot) {
                if (l.unitCost == null || l.qty.signum() == 0) continue;
                events.add(purchaseEvent(l.date, l.date, l.qty, l.unitCost, l.source.toString() + " (carried into " + run.year + ")"));
            }
            for (Arrived a : arrivedInYear) {
                // The row keeps the original acquisition date; quantity and cost are as stated on the day the lot arrived.
                events.add(purchaseEvent(a.acquired(), a.arrivedOn(), a.qty(), a.unitCost(), a.source() + " (transferred in)"));
            }
            for (Bought b : boughtInYear) events.add(purchaseEvent(b.trade().date(), b.trade().date(), b.trade().quantity(), b.unitCost(), b.trade().source().toString()));
            for (Sold s : soldInYear) {
                Restated r = restate(s.trade().date(), s.trade().quantity(), s.unitProceeds());
                events.add(new SecurityEvent(SecurityEvent.Kind.SALE, s.trade().date(), r.qty, r.unit, null, s.trade().source().toString()));
            }
            verifyBalance(events);

            boolean fund = instrument.assetClass() == Instrument.AssetClass.ETF || instrument.assetClass() == Instrument.AssetClass.FUND;
            String code = instrument.symbol() != null && instrument.symbol().length() <= 10 ? instrument.symbol() : null;
            String name = instrument.name() != null ? instrument.name() : instrument.symbol();
            if (name != null && name.length() > 100) {
                run.diagnostics.add(Diagnostic.warning("NAME_TRUNCATED", "Name of " + instrument.key() + " truncated to 100 characters",
                        entries.get(0).source()));
                name = name.substring(0, 100);
            }
            return KdvpItem.ofSecurity(SheetType.PLVP, new Security(instrument.isin(), code, name, fund, events));
        }

        private record Restated(BigDecimal qty, BigDecimal unit) {}

        /** Units valid after the year's splits that happen after {@code date} (splits are effective at the start of their day). */
        private Restated restate(LocalDate date, BigDecimal qty, BigDecimal unit) {
            BigDecimal num = BigDecimal.ONE, den = BigDecimal.ONE;
            for (Split s : splitsInYear) {
                if (s.date().isAfter(date)) { num = num.multiply(s.newQty()); den = den.multiply(s.oldQty()); }
            }
            return new Restated(qty.multiply(num).divide(den, SCALE, RoundingMode.HALF_UP),
                    unit.multiply(den).divide(num, SCALE, RoundingMode.HALF_UP));
        }

        /** @param restateDate splits after this day are applied to quantity and price; {@code date} is what the row shows */
        private SecurityEvent purchaseEvent(LocalDate date, LocalDate restateDate, BigDecimal qty, BigDecimal unit, String ref) {
            Restated r = restate(restateDate, qty, unit);
            return new SecurityEvent(SecurityEvent.Kind.PURCHASE, date, r.qty, r.unit, "B", ref);
        }

        /** The sheet's final stock must equal what the FIFO pool still holds (known-cost lots). */
        private void verifyBalance(List<SecurityEvent> events) {
            BigDecimal sheet = BigDecimal.ZERO;
            for (SecurityEvent e : events) sheet = e.kind() == SecurityEvent.Kind.PURCHASE ? sheet.add(e.quantity()) : sheet.subtract(e.quantity());
            BigDecimal held = pool.stream().filter(l -> l.unitCost != null).map(l -> l.qty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sheet.subtract(held).abs().compareTo(new BigDecimal("0.000001")) > 0) {
                throw new Failure("INTERNAL_INCONSISTENCY", "Sheet stock " + sheet.toPlainString() + " differs from FIFO holdings "
                        + held.toPlainString() + " for " + instrument.key(), entries.get(0).source());
            }
        }
    }

    // ---- dividends --------------------------------------------------------------------------------------------

    private List<Dividend> dividends(Run run, Ledger ledger, LocalDate from, LocalDate to) {
        List<Dividend> out = new ArrayList<>();
        Set<String> noted = new HashSet<>();
        for (LedgerEntry e : ledger.entries()) {
            if (!(e instanceof DividendPayment d) || d.date().isBefore(from) || d.date().isAfter(to)) continue;
            try {
                if (d.sourceCountry() == null) {
                    throw new Failure("MISSING_COUNTRY", "Dividend of " + d.instrument().key() + " on " + d.date()
                            + " has no source country (needed for Doh-Div)", d.source());
                }
                BigDecimal gross = eur(run, d.gross(), d.currency(), d.date(), d, "gross dividend");
                BigDecimal tax = d.withholdingTax().signum() == 0 ? BigDecimal.ZERO
                        : eur(run, d.withholdingTax(), d.currency(), d.date(), d, "withholding tax");
                String name = d.instrument().name() != null ? d.instrument().name() : d.instrument().symbol();
                out.add(new Dividend(d.date(), null, name, null, d.sourceCountry(), "1", gross, tax, d.sourceCountry(), null, d.source().toString()));
                if (noted.add(d.instrument().key())) {
                    run.diagnostics.add(Diagnostic.warning("PAYER_DETAILS_MISSING", "Payer address and tax number of "
                            + d.instrument().key() + " are not in broker statements; complete them in eDavki", d.source()));
                }
            } catch (Failure f) {
                run.diagnostics.add(f.diagnostic);
                run.diagnostics.add(Diagnostic.error("DIVIDEND_EXCLUDED", "Dividend of " + d.instrument().key() + " on " + d.date()
                        + " is left out of the Doh-Div because of the error above", d.source()));
            }
        }
        return out;
    }
}
