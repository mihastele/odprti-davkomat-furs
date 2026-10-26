package si.opendavki.broker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Derives stable ledger ids for statements that lack transaction ids: a hash of the row's business content plus an
 * occurrence counter, so re-importing an overlapping statement yields the same ids (and is de-duplicated) while two
 * genuinely identical fills inside one file stay distinct.
 */
public final class EntryIds {
    private final String prefix;
    private final Map<String, Integer> occurrences = new HashMap<>();

    public EntryIds(String prefix) {
        this.prefix = prefix;
    }

    public String derive(String... fields) {
        String content = String.join("\u001f", fields);
        int n = occurrences.merge(content, 1, Integer::sum);
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((content + "\u001f" + n).getBytes(StandardCharsets.UTF_8));
            return prefix + "-" + HexFormat.of().formatHex(h, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
