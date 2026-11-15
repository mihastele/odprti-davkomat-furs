package si.opendavki.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsServiceTest {
    @TempDir Path dir;

    private SettingsService service() { return new SettingsService(dir.resolve("OpenDavki/.env")); }

    @Test void viewShowsDefaultsAndTheSettingsPath() throws IOException {
        Map<String, Object> v = service().view();
        assertEquals(service().file().toString(), v.get("path"));
        assertEquals(false, v.get("exists"));
        @SuppressWarnings("unchecked")
        Map<String, String> values = (Map<String, String>) v.get("values");
        assertEquals("ECB", values.get("OPENDAVKI_FX_SOURCE"));
        assertTrue(((java.util.List<?>) v.get("keys")).contains("OPENDAVKI_TIMEZONE"));
    }

    @Test void saveValidatesAndWritesOnlyTheChangedKeys() throws IOException {
        SettingsService s = service();
        Files.createDirectories(s.file().getParent());
        Files.writeString(s.file(), "# comment\nOPENDAVKI_TIMEZONE=UTC\n");
        s.save(Map.of("OPENDAVKI_INCLUDE_FEES", "false", "OPENDAVKI_TIMEZONE", "Europe/Ljubljana"));
        String text = Files.readString(s.file());
        assertTrue(text.contains("# comment"));
        assertTrue(text.contains("OPENDAVKI_INCLUDE_FEES=false"));
        assertEquals("false", si.opendavki.fx.DotEnv.read(s.file()).get("OPENDAVKI_INCLUDE_FEES"));
        assertEquals("Europe/Ljubljana", si.opendavki.fx.DotEnv.read(s.file()).get("OPENDAVKI_TIMEZONE"));
    }

    @Test void saveRejectsUnknownKeysAndInvalidCombinationsWithoutChangingTheFile() throws IOException {
        SettingsService s = service();
        Files.createDirectories(s.file().getParent());
        Files.writeString(s.file(), "OPENDAVKI_TIMEZONE=UTC\n");
        assertThrows(IllegalArgumentException.class, () -> s.save(Map.of("OPENDAVKI_HOME", "/x")));
        assertThrows(IllegalArgumentException.class, () -> s.save(Map.of("OPENDAVKI_INCLUDE_FEES", "sometimes")));
        assertThrows(IllegalArgumentException.class, () -> s.save(Map.of("OPENDAVKI_TIMEZONE", "Mars/Base")));
        assertThrows(IllegalArgumentException.class, () -> s.save(Map.of("OPENDAVKI_FX_SOURCE", "FILE")));
        assertEquals("UTC", si.opendavki.fx.DotEnv.read(s.file()).get("OPENDAVKI_TIMEZONE"));
    }

    @Test void fileUploadsAreWrittenUnderTheSettingsDirectoryAndTraversalIsRejected() throws IOException {
        SettingsService s = service();
        Map<String, Object> saved = s.saveFile("instruments", "my-instruments.csv",
                "match,isin\nCUSIPX,IE00BFMXXD54\n".getBytes(StandardCharsets.UTF_8));
        Path path = Path.of((String) saved.get("path"));
        assertTrue(Files.isRegularFile(path));
        assertTrue(path.startsWith(s.file().getParent().resolve("files/instruments")));
        assertThrows(IllegalArgumentException.class, () -> s.saveFile("instruments", "../escape.csv", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> s.saveFile("unknown", "x.csv", new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> s.saveFile("profile", "bad.json", "not json".getBytes(StandardCharsets.UTF_8)));
    }
}
