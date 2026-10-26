package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Doh-Div {@code CorpDataDetail}: acquisition/disposal detail rows belonging to a {@link CorpData} id. */
public record CorpDataDetail(Integer id, LocalDate purchDate, String purchType, BigDecimal purchAmount, BigDecimal purchShare,
                             BigDecimal valueOfPurchased, BigDecimal valueAtPurchase, BigDecimal soldAmount,
                             BigDecimal soldShare, BigDecimal soldValue, BigDecimal soldSharesValueAtPurchase,
                             BigDecimal grossSoldValue) {}
