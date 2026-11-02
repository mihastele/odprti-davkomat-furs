package si.opendavki.broker;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Map;

/**
 * Settings an adapter may need that are not part of the statement. Adapters whose statements carry timestamps with an
 * offset (Trading 212: UTC) convert them to the date in {@code zone}; statements with local dates ignore it.
 *
 * @param zone {@code OPENDAVKI_TIMEZONE}, default UTC. Which zone FURS regards as decisive for the date of a trade near
 *             midnight is not confirmed (BACKLOG R-02), so the default keeps the statement's own date.
 */
public record AdapterContext(ZoneId zone) {

    public static AdapterContext defaults() {
        return new AdapterContext(ZoneId.of("UTC"));
    }

    public static AdapterContext from(Map<String, String> env) {
        String z = env.getOrDefault("OPENDAVKI_TIMEZONE", "UTC").trim();
        try {
            return new AdapterContext(ZoneId.of(z.isEmpty() ? "UTC" : z));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("OPENDAVKI_TIMEZONE is not a valid time zone: '" + z + "' (example: Europe/Ljubljana)");
        }
    }
}
