package si.opendavki.broker.testkit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import si.opendavki.broker.BrokerAdapter;
import si.opendavki.broker.ImportResult;
import si.opendavki.broker.StatementSource;
import si.opendavki.json.Json;

/**
 * Regression kit for broker adapters. A fixture is a statement plus an expected-result JSON next to it
 * ({@code foo.csv} -> {@code foo.expected.json}). The check fails on any change to entries, diagnostics or row
 * accounting. Regenerate intentionally with {@code -Dodv.updateFixtures=true} and review the diff.
 */
public final class AdapterFixtures {
    private AdapterFixtures() {}

    public static void assertFixture(BrokerAdapter adapter, Path statement) throws IOException {
        StatementSource src = StatementSource.read(statement);
        assertTrue(adapter.accepts(src), adapter.id() + " should accept " + statement);
        ImportResult result = adapter.read(src); // throws if the adapter lost a row
        assertEquals(result.stats().rowsRead(),
                result.stats().rowsMapped() + result.stats().rowsSkipped() + result.stats().rowsFailed());

        String actual = Json.MAPPER.writeValueAsString(result) + "\n";
        Path expected = statement.resolveSibling(statement.getFileName().toString().replaceFirst("\\.[^.]+$", "") + ".expected.json");
        if (Boolean.getBoolean("odv.updateFixtures")) {
            Files.writeString(expected, actual, StandardCharsets.UTF_8);
            return;
        }
        assertTrue(Files.exists(expected), "missing " + expected + " (run with -Dodv.updateFixtures=true to create it)");
        assertEquals(Files.readString(expected, StandardCharsets.UTF_8), actual, "output differs from " + expected);
    }
}
