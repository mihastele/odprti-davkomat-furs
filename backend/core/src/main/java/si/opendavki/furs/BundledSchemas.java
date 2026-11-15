package si.opendavki.furs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The official XSDs shipped inside the application jar.
 *
 * <p>The validators take a directory, not a classpath URL: XSD imports (EDP-Common) must resolve as local files and the
 * secure parser is configured to allow the {@code file} protocol only. On first use the bundled bytes are therefore
 * extracted to the user cache and verified against the official checksums; a cache that already matches is reused.
 *
 * <p>Only the schemas needed by {@link FormType} and their {@code EDP-Common-1.xsd} import are extracted. The filtered
 * {@code SHA256SUMS} written next to them is derived from the official file, never hand-edited.
 */
public final class BundledSchemas {
    static final String RESOURCE_ROOT = "/schemas/";
    static final String SUMS = "SHA256SUMS";

    private BundledSchemas() {}

    /** The XSD file names required to validate every form this application writes. */
    public static List<String> requiredFiles() {
        List<String> names = new ArrayList<>(Arrays.stream(FormType.values()).map(f -> f.xsdFileName).toList());
        names.add("EDP-Common-1.xsd");
        return List.copyOf(names);
    }

    /** Whether the jar contains every bundled XSD and its checksum line. */
    public static boolean available() {
        for (String name : requiredFiles()) {
            if (BundledSchemas.class.getResource(RESOURCE_ROOT + name) == null) return false;
        }
        return BundledSchemas.class.getResource(RESOURCE_ROOT + SUMS) != null;
    }

    /**
     * Returns a directory containing the required XSDs and a {@code SHA256SUMS} that covers exactly those files.
     * Reuses an already valid cache; replaces an incomplete or modified one.
     *
     * @throws IOException when the jar has no bundled schemas or the cache cannot be written
     */
    public static Path ensure(Path destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("destination must not be null");
        if (!available()) {
            throw new IOException("This build has no bundled FURS schemas. Use --schema-dir with the official XSD directory.");
        }
        Path target = destination.toAbsolutePath().normalize();
        if (isComplete(target)) return target;

        Path parent = target.getParent();
        if (parent == null) throw new IOException("Invalid schema cache directory: " + target);
        Files.createDirectories(parent);
        Path temp = Files.createTempDirectory(parent, "schemas-");
        try {
            List<String> sums = extract(temp);
            writeFilteredSums(temp, sums);
            if (!isComplete(temp)) throw new IOException("Bundled schemas did not verify after extraction; the jar is corrupt");
            deleteRecursively(target);
            Files.move(temp, target);
            return target;
        } catch (IOException | RuntimeException e) {
            deleteRecursively(temp);
            throw e;
        }
    }

    private static List<String> extract(Path destination) throws IOException {
        List<String> sums = new ArrayList<>();
        Set<String> required = new HashSet<>(requiredFiles());
        try (InputStream in = resource(SUMS)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) throw new IOException("Bundled " + SUMS + " is malformed: " + line);
                String name = parts[1].replaceFirst("^\\*", "");
                if (required.contains(name)) sums.add(line.trim());
            }
        }
        if (sums.size() != required.size()) {
            throw new IOException("Bundled " + SUMS + " does not list every required schema");
        }
        for (String name : requiredFiles()) {
            Path file = destination.resolve(name);
            try (InputStream in = resource(name)) {
                Files.copy(in, file);
            }
            String expected = expected(sums, name);
            String actual = SchemaChecksums.sha256(Files.readAllBytes(file));
            if (!actual.equals(expected)) {
                throw new IOException("Bundled " + name + " has sha256=" + actual + " but " + SUMS + " lists " + expected);
            }
        }
        return sums;
    }

    private static void writeFilteredSums(Path destination, List<String> sums) throws IOException {
        Files.writeString(destination.resolve(SUMS), String.join("\n", sums) + "\n", StandardCharsets.UTF_8);
    }

    private static String expected(List<String> sums, String name) throws IOException {
        for (String line : sums) {
            String[] parts = line.split("\\s+", 2);
            if (parts[1].replaceFirst("^\\*", "").equals(name)) return parts[0];
        }
        throw new IOException("Bundled " + SUMS + " has no line for " + name);
    }

    private static boolean isComplete(Path dir) {
        try {
            if (!Files.isRegularFile(dir.resolve(SUMS))) return false;
            for (String name : requiredFiles()) {
                if (!Files.isRegularFile(dir.resolve(name))) return false;
            }
            List<SchemaChecksums.Entry> entries = SchemaChecksums.verify(dir);
            if (entries.size() != requiredFiles().size()) return false;
            return entries.stream().allMatch(e -> e.status() == SchemaChecksums.Status.OK);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = BundledSchemas.class.getResourceAsStream(RESOURCE_ROOT + name);
        if (in == null) throw new IOException("Bundled schema resource not found: " + RESOURCE_ROOT + name);
        return in;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
