package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Reduction of a positive tax base for a transferred loss (art. 97(3) ZDoh-2). */
public record TaxBaseDecrease(String orderNumber, LocalDate orderDate, String taxOfficeId, String taxOfficeName,
                              BigDecimal loss) {}
