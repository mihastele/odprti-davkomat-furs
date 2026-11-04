package si.opendavki.model;

import java.util.List;

/** Input to the Doh-Obr writer: interest income for one tax year. Separate from dividends and capital gains by design. */
public record ObrReport(Taxpayer taxpayer, int year, boolean resident, List<InterestRow> interest) {
    public ObrReport {
        interest = interest == null ? List.of() : List.copyOf(interest);
    }

    public ObrReport(Taxpayer taxpayer, int year, List<InterestRow> interest) {
        this(taxpayer, year, true, interest);
    }
}
