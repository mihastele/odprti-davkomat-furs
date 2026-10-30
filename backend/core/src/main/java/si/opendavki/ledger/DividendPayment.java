package si.opendavki.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

/** A dividend: gross amount and foreign tax withheld, both in {@code currency}. Never netted against trade results. */
public record DividendPayment(String id, String broker, String account, LocalDate date, SourceRef source,
                              Instrument instrument, BigDecimal gross, BigDecimal withholdingTax, String currency,
                              String sourceCountry) implements LedgerEntry {

    public DividendPayment {
        LedgerChecks.common(id, broker, account, date, instrument);
        if (gross == null || gross.signum() < 0) throw new IllegalArgumentException("gross must be >= 0");
        if (withholdingTax == null) withholdingTax = BigDecimal.ZERO;
        if (withholdingTax.signum() < 0) throw new IllegalArgumentException("withholdingTax must be >= 0");
        LedgerChecks.currency(currency);
        if (sourceCountry != null && !sourceCountry.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("sourceCountry must be ISO 3166 alpha-2: " + sourceCountry);
        }
    }

    @Override public int dayOrder() { return 4; }

    @Override public LedgerEntry mapInstruments(UnaryOperator<Instrument> f) {
        return new DividendPayment(id, broker, account, date, source, f.apply(instrument), gross, withholdingTax, currency, sourceCountry);
    }
}
