package si.opendavki.furs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaChecksumsTest {
    // sha256("hello") and sha256("hello\n") computed independently with sha256sum
    private static final String HELLO = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";

    @Test void theCommittedOfficialSchemasMatchTheirChecksums() throws IOException {
        List<SchemaChecksums.Entry> entries = SchemaChecksums.verify(Path.of("../../schemas"));
        assertTrue(entries.size() >= 3);
        assertTrue(entries.stream().allMatch(e -> e.status() == SchemaChecksums.Status.OK), entries.toString());
        for (FormType f : FormType.values()) {
            assertTrue(entries.stream().anyMatch(e -> e.name().equals(f.xsdFileName)), f.xsdFileName + " must be checksummed");
        }
    }

    @Test void editedAndMissingFilesAreDetected(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a.xsd"), "hello");
        Files.writeString(dir.resolve("b.xsd"), "hello");
        Files.writeString(dir.resolve(SchemaChecksums.FILE_NAME),
                "# comment\n" + HELLO + "  a.xsd\n" + HELLO + " *b.xsd\n" + HELLO + "  gone.xsd\n", StandardCharsets.UTF_8);
        assertEquals(SchemaChecksums.Status.OK, SchemaChecksums.verify(dir).get(0).status());
        assertEquals(SchemaChecksums.Status.OK, SchemaChecksums.verify(dir).get(1).status()); // binary-mode marker accepted
        assertEquals(SchemaChecksums.Status.MISSING, SchemaChecksums.verify(dir).get(2).status());
        Files.writeString(dir.resolve("a.xsd"), "hello\n");
        SchemaChecksums.Entry changed = SchemaChecksums.verify(dir).get(0);
        assertEquals(SchemaChecksums.Status.MISMATCH, changed.status());
        assertEquals(HELLO, changed.expected());
        assertNotEquals(HELLO, changed.actual());
    }

    @Test void noChecksumFileMeansUnverifiedNotFine(@TempDir Path dir) {
        assertThrows(IOException.class, () -> SchemaChecksums.verify(dir));
        assertThrows(IOException.class, () -> {
            Files.writeString(dir.resolve(SchemaChecksums.FILE_NAME), "nothex  a.xsd\n");
            SchemaChecksums.verify(dir);
        });
    }

    @Test void sha256MatchesTheKnownDigest() {
        assertEquals(HELLO, SchemaChecksums.sha256("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
