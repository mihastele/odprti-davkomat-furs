package si.opendavki.model;

import java.util.List;

/**
 * Input to the Doh-Div writer: dividend income for one tax year. Kept separate from capital gains by design.
 *
 * @param resident defaults to true (Doh-Div is filed by Slovenian tax residents)
 */
public record DivReport(Taxpayer taxpayer, int year, Boolean resident, String residentCountry, String email, String phone,
                        Boolean selfReport, String notes,
                        List<Dividend> dividends, List<CorpData> corpData, List<CorpDataDetail> corpDataDetails,
                        SubseqSubmission subsequentSubmission) {
    public DivReport {
        if (resident == null) resident = true;
        dividends = dividends == null ? List.of() : List.copyOf(dividends);
        corpData = corpData == null ? List.of() : List.copyOf(corpData);
        corpDataDetails = corpDataDetails == null ? List.of() : List.copyOf(corpDataDetails);
    }

    public DivReport(Taxpayer taxpayer, int year, List<Dividend> dividends) {
        this(taxpayer, year, null, null, null, null, null, null, dividends, null, null, null);
    }
}
