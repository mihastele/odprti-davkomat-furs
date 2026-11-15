package si.opendavki.app;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppPathsTest {
    @TempDir Path tmp;

    @Test void windowsUsesRoamingConfigAndLocalCache() {
        Path home = tmp.resolve("home");
        Path roaming = tmp.resolve("roaming");
        Path local = tmp.resolve("local");
        AppPaths p = AppPaths.forEnvironment(Map.of("APPDATA", roaming.toString(), "LOCALAPPDATA", local.toString()),
                "Windows 11", home);
        assertEquals(roaming.resolve("OpenDavki"), p.configDir());
        assertEquals(local.resolve("OpenDavki"), p.cacheDir());
        assertEquals(p.configDir().resolve(".env"), p.envFile());
        assertEquals(p.cacheDir().resolve("schemas"), p.schemaDir());
    }

    @Test void macUsesApplicationSupportAndCaches() {
        Path home = tmp.resolve("home");
        AppPaths p = AppPaths.forEnvironment(Map.of(), "Mac OS X", home);
        assertEquals(home.resolve("Library/Application Support/OpenDavki"), p.configDir());
        assertEquals(home.resolve("Library/Caches/OpenDavki"), p.cacheDir());
    }

    @Test void linuxHonoursXdgVariables() {
        Path home = tmp.resolve("home");
        Path cfg = tmp.resolve("cfg");
        AppPaths p = AppPaths.forEnvironment(Map.of("XDG_CONFIG_HOME", cfg.toString(), "XDG_CACHE_HOME", "cache"),
                "Linux", home);
        assertEquals(cfg.resolve("opendavki"), p.configDir());
        assertEquals(home.resolve("cache/opendavki"), p.cacheDir());
    }

    @Test void portableHomeOverridesEverything() {
        Path home = tmp.resolve("home");
        Path portable = tmp.resolve("usb");
        AppPaths p = AppPaths.forEnvironment(Map.of("OPENDAVKI_HOME", portable.toString(), "APPDATA", "ignored"),
                "Windows 10", home);
        assertEquals(portable, p.home());
        assertEquals(portable, p.configDir());
        assertEquals(portable.resolve("cache"), p.cacheDir());
        assertEquals(portable.resolve("cache/fx"), p.fxCacheDir());
    }

    @Test void relativeXdgPathsAreResolvedAgainstHome() {
        Path home = tmp.resolve("home");
        AppPaths p = AppPaths.forEnvironment(Map.of("XDG_CONFIG_HOME", "cfg"), "Linux", home);
        assertEquals(home.resolve("cfg/opendavki"), p.configDir());
    }

    @Test void aRelativePortableHomeIsResolvedAgainstTheWorkingDirectory() {
        AppPaths p = AppPaths.forEnvironment(Map.of("OPENDAVKI_HOME", "usb"), "Linux", tmp.resolve("home"));
        assertEquals(Path.of("usb").toAbsolutePath().normalize(), p.home());
    }
}
