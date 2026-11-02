package si.opendavki.broker.profile;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import si.opendavki.json.Json;

/** Reads and validates profile files; a profile that does not validate is rejected with the file name. */
public final class ProfileLoader {
    private ProfileLoader() {}

    public static Profile load(Path file) throws IOException {
        return parse(Files.readString(file), file.toString());
    }

    /** @param name used in error messages (a file name or an upload name) */
    public static Profile parse(String json, String name) {
        try {
            return Json.MAPPER.readValue(json, Profile.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            Throwable cause = e.getCause() != null && e.getCause() instanceof IllegalArgumentException ? e.getCause() : e;
            throw new IllegalArgumentException(name + ": " + cause.getMessage().lines().findFirst().orElse("invalid profile"), e);
        }
    }
}
