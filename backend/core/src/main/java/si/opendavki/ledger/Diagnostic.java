package si.opendavki.ledger;

/** A finding about the input data. Adapters report problems here instead of dropping or guessing. */
public record Diagnostic(Severity severity, String code, String message, SourceRef source) {
    public enum Severity { INFO, WARNING, ERROR }

    public static Diagnostic error(String code, String message, SourceRef s) { return new Diagnostic(Severity.ERROR, code, message, s); }
    public static Diagnostic warning(String code, String message, SourceRef s) { return new Diagnostic(Severity.WARNING, code, message, s); }
    public static Diagnostic info(String code, String message, SourceRef s) { return new Diagnostic(Severity.INFO, code, message, s); }

    @Override public String toString() {
        return severity + " " + code + (source != null ? " [" + source + "]" : "") + ": " + message;
    }
}
