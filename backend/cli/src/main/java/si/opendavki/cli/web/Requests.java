package si.opendavki.cli.web;

import java.util.List;

/** JSON request bodies of the local API. Unknown properties are rejected (the shared mapper is strict). */
final class Requests {
    private Requests() {}

    record File(String name, String contentBase64) {}

    record Upload(String name, String text) {}

    record Import(List<File> files, String adapter, List<Upload> profiles) {}

    record Describe(String name, String contentBase64, List<String> keep) {}

    /** The ledger is referred to by id: it stays on the server so amounts never pass through JavaScript numbers. */
    record Report(String ledgerId, int year, String taxNumber, boolean allowIncomplete) {}

    /** Opens a ledger JSON file written by `import`. */
    record LoadLedger(String text) {}
}
