package si.opendavki.app;

import java.nio.file.Path;
import java.util.Map;

/**
 * The directories OpenDavki uses when it is not started from a development checkout.
 *
 * <p>They are deliberately separate from the current working directory: a packaged application is started by the desktop
 * environment, frequently with an arbitrary working directory. {@code OPENDAVKI_HOME} overrides all three directories so
 * the same jar can run in portable mode (for example from a USB stick).
 *
 * @param home      the user's home directory, or {@code OPENDAVKI_HOME} when set
 * @param configDir where the settings file lives
 * @param cacheDir  where downloaded exchange rates and extracted schemas live
 */
public record AppPaths(Path home, Path configDir, Path cacheDir) {

    public static final String HOME_ENV = "OPENDAVKI_HOME";

    public AppPaths {
        if (home == null) throw new IllegalArgumentException("home must not be null");
        if (configDir == null) throw new IllegalArgumentException("configDir must not be null");
        if (cacheDir == null) throw new IllegalArgumentException("cacheDir must not be null");
    }

    /** Paths from the real process environment and the current Java home property. */
    public static AppPaths current() {
        return forEnvironment(System.getenv(), System.getProperty("os.name", ""), userHome());
    }

    /** The settings file this installation should use when the user has not chosen one. */
    public Path envFile() {
        return configDir.resolve(".env");
    }

    /** Root of the extracted official XSDs. */
    public Path schemaDir() {
        return cacheDir.resolve("schemas");
    }

    /** Cache for downloaded exchange-rate tables. */
    public Path fxCacheDir() {
        return cacheDir.resolve("fx");
    }

    /**
     * Builds paths for a specific environment. Visible for tests, which must not depend on the machine they run on.
     *
     * @param env     environment variables (may be empty)
     * @param osName  {@code os.name}; Windows, macOS and everything else (XDG) are recognised
     * @param userHome the user's home directory
     */
    public static AppPaths forEnvironment(Map<String, String> env, String osName, Path userHome) {
        if (env == null) throw new IllegalArgumentException("env must not be null");
        if (userHome == null) throw new IllegalArgumentException("userHome must not be null");
        Path home = userHome.toAbsolutePath().normalize();

        String override = trimToNull(env.get(HOME_ENV));
        if (override != null) {
            // Portable mode is relative to where the application was started, not to the user's home.
            Path portable = Path.of(override).toAbsolutePath().normalize();
            return new AppPaths(portable, portable, portable.resolve("cache"));
        }

        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            Path roaming = absolute(home, trimToNull(env.get("APPDATA")) == null ? home.resolve("AppData/Roaming").toString() : env.get("APPDATA"));
            Path local = absolute(home, trimToNull(env.get("LOCALAPPDATA")) == null ? home.resolve("AppData/Local").toString() : env.get("LOCALAPPDATA"));
            return new AppPaths(home, roaming.resolve("OpenDavki"), local.resolve("OpenDavki"));
        }
        if (os.contains("mac")) {
            return new AppPaths(home,
                    home.resolve("Library/Application Support/OpenDavki"),
                    home.resolve("Library/Caches/OpenDavki"));
        }
        Path configHome = absolute(home, trimToNull(env.get("XDG_CONFIG_HOME")) == null ? home.resolve(".config").toString() : env.get("XDG_CONFIG_HOME"));
        Path cacheHome = absolute(home, trimToNull(env.get("XDG_CACHE_HOME")) == null ? home.resolve(".cache").toString() : env.get("XDG_CACHE_HOME"));
        return new AppPaths(home, configHome.resolve("opendavki"), cacheHome.resolve("opendavki"));
    }

    private static Path userHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.isBlank()) throw new IllegalStateException("user.home is not set");
        return Path.of(value);
    }

    /** XDG variables are allowed to be relative to the home directory. */
    private static Path absolute(Path home, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : home.resolve(path)).normalize();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
