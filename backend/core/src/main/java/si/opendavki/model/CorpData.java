package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Doh-Div {@code CorpData}: summary of a disposal/payout from a company (capital repayment case). */
public record CorpData(Integer id, String tradeId, LocalDate soldDate, BigDecimal corpAmount, BigDecimal corpShare,
                       BigDecimal corpSoldAmount, BigDecimal corpSoldShare, BigDecimal nominalTotalValue, BigDecimal sum) {}
