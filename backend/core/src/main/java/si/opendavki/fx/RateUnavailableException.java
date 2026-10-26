package si.opendavki.fx;

/** No usable rate: the conversion must not proceed on a guess. */
public final class RateUnavailableException extends RuntimeException {
    public RateUnavailableException(String message) { super(message); }
    public RateUnavailableException(String message, Throwable cause) { super(message, cause); }
}
