package si.opendavki.cli;

/** The user's input or settings are wrong (as opposed to the data containing findings): reported as such, exit code 2. */
final class InputException extends RuntimeException {
    InputException(String message) { super(message); }
}
