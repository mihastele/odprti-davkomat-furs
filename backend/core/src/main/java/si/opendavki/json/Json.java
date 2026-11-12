package si.opendavki.json;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The one ObjectMapper configuration used for report inputs and ledger files: exact BigDecimal round-trips
 * (never double), ISO dates, and strict input (unknown properties are errors, not silently ignored).
 */
public final class Json {
    /**
     * Jackson's default pretty printer indents objects with the platform line separator, so the same ledger would
     * serialise differently on Windows (CRLF) and elsewhere (LF). Pin LF so generated files and adapter fixtures are
     * byte-identical everywhere; arrays keep Jackson's inline {@code [ {} ]} layout.
     */
    private static final DefaultPrettyPrinter PRETTY_PRINTER = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));

    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
            .setDefaultPrettyPrinter(PRETTY_PRINTER)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Json() {}
}
