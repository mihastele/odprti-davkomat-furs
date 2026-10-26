package si.opendavki.model;

import java.time.LocalDate;
import java.util.List;

/**
 * One security (ISIN) with its purchases and sales.
 *
 * @param stockExchangeName only for management-contract sheets (PLVPGB*): the brokerage firm
 * @param resolution        optional general-meeting resolution number (full sheets only)
 */
public record Security(String isin, String code, String name, boolean fund,
                       String stockExchangeName, String resolution, LocalDate resolutionDate,
                       List<SecurityEvent> events) {
    public Security {
        if (isin == null || !isin.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) {
            throw new IllegalArgumentException("invalid ISIN: " + isin);
        }
        events = events == null ? List.of() : List.copyOf(events);
    }

    public Security(String isin, String code, String name, boolean fund, List<SecurityEvent> events) {
        this(isin, code, name, fund, null, null, null, events);
    }
}
