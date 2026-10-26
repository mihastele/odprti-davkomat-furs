package si.opendavki.model;

import java.util.List;

/** Input to the Doh-KDVP writer: capital gains from securities for one tax year. */
public record KdvpReport(Taxpayer taxpayer, int year, boolean resident, boolean hasForeignTax, List<Security> securities) {
    public KdvpReport {
        securities = List.copyOf(securities);
    }
}
