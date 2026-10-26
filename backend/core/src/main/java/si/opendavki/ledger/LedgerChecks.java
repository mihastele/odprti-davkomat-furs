package si.opendavki.ledger;

import java.time.LocalDate;

/** Shared constructor validation for ledger entries. */
final class LedgerChecks {
    private LedgerChecks() {}

    static void common(String id, String broker, String account, LocalDate date, Instrument instrument) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is required");
        if (broker == null || broker.isBlank()) throw new IllegalArgumentException("broker is required");
        if (account == null || account.isBlank()) throw new IllegalArgumentException("account is required");
        if (date == null) throw new IllegalArgumentException("date is required");
        if (instrument == null) throw new IllegalArgumentException("instrument is required");
    }

    static void currency(String c) {
        if (c == null || !c.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be ISO 4217 (3 capital letters): " + c);
    }
}
