package si.opendavki.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Reduction of a positive tax base for a loss realised on the disposal of real estate (Doh-KDVP {@code TaxRelief}). */
public record TaxRelief(String orderNumber, LocalDate orderDate, LocalDate acquirementDate, LocalDate expropriationDate,
                        BigDecimal loss, BigDecimal profit, BigDecimal incomeTax, Boolean prefilled) {}
