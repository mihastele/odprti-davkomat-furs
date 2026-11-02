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
        try {
            return Json.MAPPER.readValue(Files.readString(file), Profile.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            Throwable cause = e.getCause() != null && e.getCause() instanceof IllegalArgumentException ? e.getCause() : e;
            throw new IllegalArgumentException(file + ": " + cause.getMessage().lines().findFirst().orElse("invalid profile"), e);
        }
    }
}
