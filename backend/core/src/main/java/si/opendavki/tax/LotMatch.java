package si.opendavki.tax;

import java.math.BigDecimal;
import java.time.LocalDate;
import si.opendavki.ledger.SourceRef;

/**
 * One slice of a sale matched to one purchase lot by FIFO: the audit unit. All EUR figures keep 12 decimals;
 * {@code gainEur = quantity * (unitProceedsEur - unitCostEur)}. Quantities are in the units valid on the sale date.
 */
public record LotMatch(String instrument, String symbol,
                       String saleEntryId, SourceRef saleSource, LocalDate saleDate,
                       String lotEntryId, SourceRef lotSource, LocalDate lotDate,
                       BigDecimal quantity, BigDecimal unitCostEur, BigDecimal unitProceedsEur, BigDecimal gainEur) {}
