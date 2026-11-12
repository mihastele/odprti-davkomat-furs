package si.opendavki.json;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    /** Generated JSON (ledger files, audit reports, adapter fixtures) must not depend on the platform's line separator. */
    @Test void prettyOutputAlwaysUsesLf() throws Exception {
        String json = Json.MAPPER.writeValueAsString(Map.of("a", 1));

        assertEquals("{\n  \"a\" : 1\n}", json);
        assertFalse(json.contains("\r"), json);
    }

    @Test void arraysStayInlineWithObjectsIndented() throws Exception {
        String json = Json.MAPPER.writeValueAsString(Map.of("items", List.of(Map.of("a", 1))));

        assertEquals("{\n  \"items\" : [ {\n    \"a\" : 1\n  } ]\n}", json);
    }
}
