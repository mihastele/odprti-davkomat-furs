package si.opendavki.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Input to the Doh-KDVP writer: capital gains for one tax year.
 *
 * @param periodStart/periodEnd default to the whole {@code year}
 * @param countryOfResidenceId  3-digit FURS code; only relevant for non-residents
 * @param attachments           names of attached documents
 */
public record KdvpReport(Taxpayer taxpayer, int year, LocalDate periodStart, LocalDate periodEnd, boolean resident,
                         String countryOfResidenceId, String countryOfResidenceName,
                         String telephone, String email, Remission remission,
                         List<KdvpItem> items, List<TaxRelief> taxRelief, List<TaxBaseDecrease> taxBaseDecrease,
                         List<String> attachments) {

    public KdvpReport {
        if (periodStart == null) periodStart = LocalDate.of(year, 1, 1);
        if (periodEnd == null) periodEnd = LocalDate.of(year, 12, 31);
        items = items == null ? List.of() : List.copyOf(items);
        taxRelief = taxRelief == null ? List.of() : List.copyOf(taxRelief);
        taxBaseDecrease = taxBaseDecrease == null ? List.of() : List.copyOf(taxBaseDecrease);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    public KdvpReport(Taxpayer taxpayer, int year, boolean resident, List<KdvpItem> items) {
        this(taxpayer, year, null, null, resident, null, null, null, null, null, items, null, null, null);
    }
}
