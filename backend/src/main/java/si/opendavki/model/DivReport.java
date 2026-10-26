package si.opendavki.model;

import java.util.List;

/** Input to the Doh-Div writer: dividend income for one tax year. Kept separate from capital gains by design. */
public record DivReport(Taxpayer taxpayer, int year, List<Dividend> dividends) {
    public DivReport {
        dividends = List.copyOf(dividends);
    }
}
