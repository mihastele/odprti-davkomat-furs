package si.opendavki.furs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Verifies the official XSDs byte-for-byte against {@code SHA256SUMS} in the schema directory, so an edited, truncated or
 * wrongly downloaded schema is noticed before it validates anything.
 */
public final class SchemaChecksums {
    public static final String FILE_NAME = "SHA256SUMS";

    public enum Status { OK, MISMATCH, MISSING }

    public record Entry(String name, Status status, String expected, String actual) {}

    private SchemaChecksums() {}

    /** Entries listed in SHA256SUMS ({@code <sha256>  <file>} per line); an absent SHA256SUMS is an error, not "all fine". */
    public static List<Entry> verify(Path schemaDir) throws IOException {
        Path sums = schemaDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(sums)) throw new IOException(sums + " not found: cannot verify the schemas");
        List<Entry> out = new ArrayList<>();
        for (String line : Files.readAllLines(sums, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.trim().split("\\s+", 2);
            if (p.length != 2 || !p[0].matches("[0-9a-f]{64}")) throw new IOException(sums + ": malformed line: " + line);
            String name = p[1].replaceFirst("^\\*", "");
            Path file = schemaDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                out.add(new Entry(name, Status.MISSING, p[0], null));
                continue;
            }
            String actual = sha256(Files.readAllBytes(file));
            out.add(new Entry(name, actual.equals(p[0]) ? Status.OK : Status.MISMATCH, p[0], actual));
        }
        return out;
    }

    /** The recorded checksum of one file, if SHA256SUMS lists it. */
    public static java.util.Optional<String> expected(Path schemaDir, String fileName) throws IOException {
        return verify(schemaDir).stream().filter(e -> e.name().equals(fileName)).map(Entry::expected).findFirst();
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
