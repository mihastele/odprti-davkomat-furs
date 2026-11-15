package si.opendavki.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotEnvWriteTest {
    @TempDir Path dir;

    @Test void updatesKeysAndPreservesCommentsAndUnrelatedLines() throws IOException {
        Path env = dir.resolve(".env");
        Files.writeString(env, """
                # keep this comment
                OPENDAVKI_FX_SOURCE=ECB
                export OPENDAVKI_INCLUDE_FEES=true # keep the explanation
                OPENDAVKI_TIMEZONE=UTC
                """);
        DotEnv.writeUpdates(env, Map.of("OPENDAVKI_FX_SOURCE", "FILE", "OPENDAVKI_INCLUDE_FEES", "false",
                "OPENDAVKI_FX_FILE", "rates.csv"));
        String text = Files.readString(env);
        assertTrue(text.contains("# keep this comment"));
        assertTrue(text.contains("OPENDAVKI_FX_SOURCE=FILE"));
        assertTrue(text.contains("export OPENDAVKI_INCLUDE_FEES=false"));
        assertTrue(text.contains("OPENDAVKI_TIMEZONE=UTC"));
        assertTrue(text.contains("OPENDAVKI_FX_FILE=rates.csv"));
        assertEquals("FILE", DotEnv.read(env).get("OPENDAVKI_FX_SOURCE"));
        assertEquals("false", DotEnv.read(env).get("OPENDAVKI_INCLUDE_FEES"));
    }

    @Test void createsTheFileAndValidatesKeysAndLineBreaks() throws IOException {
        Path env = dir.resolve("new/.env");
        DotEnv.writeUpdates(env, Map.of("OPENDAVKI_TIMEZONE", "Europe/Ljubljana"));
        assertEquals("Europe/Ljubljana", DotEnv.read(env).get("OPENDAVKI_TIMEZONE"));
        assertThrows(IllegalArgumentException.class, () -> DotEnv.writeUpdates(env, Map.of("bad-key", "x")));
        assertThrows(IllegalArgumentException.class, () -> DotEnv.writeUpdates(env, Map.of("OPENDAVKI_TIMEZONE", "a\nb")));
    }
}
