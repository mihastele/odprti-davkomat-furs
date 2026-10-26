package si.opendavki.ledger;

/**
 * Where a ledger entry came from: adapter, statement file name, the file's SHA-256 and the line/row.
 * Every number in a declaration must be traceable through this to the original statement.
 */
public record SourceRef(String adapter, String file, String fileSha256, int line) {
    @Override public String toString() {
        return file + ":" + line;
    }
}
