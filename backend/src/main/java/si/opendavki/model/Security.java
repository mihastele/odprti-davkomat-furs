package si.opendavki.model;

import java.util.List;

/** One security (ISIN) with its chronologically ordered purchases and sales. */
public record Security(String isin, String code, String name, boolean fund, List<SecurityEvent> events) {
    public Security {
        if (isin == null || !isin.matches("[A-Z]{2}[A-Z0-9]{9}\\d")) {
            throw new IllegalArgumentException("invalid ISIN: " + isin);
        }
        events = List.copyOf(events);
    }
}
