package si.opendavki.furs;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundledSchemasTest {
    @TempDir Path dir;

    @Test void extractsTheRequiredSchemasAndTheirFilteredChecksums() throws IOException {
        Path schemas = BundledSchemas.ensure(dir.resolve("cache/schemas"));
        for (String name : BundledSchemas.requiredFiles()) {
            assertTrue(Files.isRegularFile(schemas.resolve(name)), name);
        }
        List<SchemaChecksums.Entry> entries = SchemaChecksums.verify(schemas);
        assertEquals(BundledSchemas.requiredFiles().size(), entries.size());
        assertTrue(entries.stream().allMatch(e -> e.status() == SchemaChecksums.Status.OK), entries.toString());
    }

    @Test void reusesAValidCacheAndRepairsAModifiedOne() throws IOException {
        Path schemas = BundledSchemas.ensure(dir.resolve("schemas"));
        Path xsd = schemas.resolve("Doh_KDVP_9.xsd");
        String before = Files.readString(xsd);

        assertEquals(schemas, BundledSchemas.ensure(schemas));
        assertEquals(before, Files.readString(xsd));

        Files.writeString(xsd, before + "\n<!-- altered -->\n");
        Path repaired = BundledSchemas.ensure(schemas);
        assertEquals(before, Files.readString(repaired.resolve("Doh_KDVP_9.xsd")));
        assertTrue(SchemaChecksums.verify(repaired).stream().allMatch(e -> e.status() == SchemaChecksums.Status.OK));
    }

    @Test void theBundledSchemaActuallyValidatesTheFormItClaims() throws IOException {
        Path schemas = BundledSchemas.ensure(dir.resolve("schemas"));
        // An empty document is invalid, but this proves the XSD and its EDP-Common import can be loaded from the extracted
        // directory rather than failing with a resolver error.
        SchemaValidator.Result r = new SchemaValidator(schemas).validate(FormType.KDVP, "<Doh_KDVP/>");
        assertFalse(r.valid());
        assertFalse(r.issues().isEmpty());
        assertFalse(r.issues().stream().anyMatch(i -> i.message().contains("schema_reference.4")), r.issues().toString());
    }
}
