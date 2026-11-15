package si.opendavki.ledger;

/**
 * A saved OpenDavki project: the canonical ledger plus the report metadata the user chose to keep.
 *
 * <p>The format is versioned because a future change to the ledger schema must not be read as if it were this one. The
 * tax number is optional and is written only when the user explicitly asks to include it.
 */
public record ProjectFile(int formatVersion, Ledger ledger, Report report) {
    public static final int CURRENT_FORMAT = 1;

    public ProjectFile {
        if (formatVersion != CURRENT_FORMAT) {
            throw new IllegalArgumentException("Unsupported project formatVersion " + formatVersion + "; this build reads version " + CURRENT_FORMAT);
        }
        if (ledger == null) throw new IllegalArgumentException("Project has no ledger");
        if (report != null && report.taxNumber() != null && !report.taxNumber().matches("\\d{8}")) {
            throw new IllegalArgumentException("Project tax number must have 8 digits");
        }
    }

    /** The optional report settings remembered with a project. */
    public record Report(Integer year, String taxNumber) {
        public Report {
            if (taxNumber != null && taxNumber.isBlank()) taxNumber = null;
        }
    }
}
