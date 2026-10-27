package si.opendavki.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import si.opendavki.fx.FxSource;
import si.opendavki.ledger.SourceRef;

/** An exchange rate applied to a ledger entry, so every EUR figure can be re-derived. */
public record RateUse(String entryId, SourceRef source, String what, String currency,
                      LocalDate requestedDate, LocalDate rateDate, BigDecimal unitsPerEur, FxSource rateSource) {}
