# Desktop application

OpenDavki has two packaged clients over the same local server, UI and ledger:

- **OpenDavki** — the browser client, with a tray icon. This is the reference client.
- **OpenDavkiDesktop** — a native JavaFX WebView window, for users who prefer not to have a browser tab. It uses the
  same Vue page, server and tax logic; it is not a second UI implementation.

Both include their own Java runtime, the Vue build and the official XSDs. They do not need Java, Maven or Node.js on the
user's machine, and they listen on nothing except `127.0.0.1`.

## Build app images

From the repository root, on the platform you are packaging for:

```bash
./packaging/package.ps1          # Windows; produces both zips under target/package/
./packaging/package.sh           # macOS/Linux; zip on macOS, tar.gz on Linux
```

The scripts run the one-command source build first (modern and legacy Vue builds), verify that the jars contain the
interface and bundled XSDs, create `jlink` runtimes with `jpackage`, start each packaged launcher once from outside the
repository (the browser client with `paths`; the JavaFX client with `--smoke`), and write `SHA256SUMS`. The app bundle
metadata uses version `1.0.0` because macOS rejects a leading zero in `--app-version`; the artifact filenames and the
project version remain `0.1.0`.

`-SkipBuild` / `--skip-build` packages an existing build. The builds are not code-signed; see the limitations below.

The JavaFX shell needs a separate **IIFE** bundle because JavaFX 21's WebView does not execute ES modules:

```bash
npm --prefix frontend run build:legacy
```

The normal `npm --prefix frontend run build` and `./build.ps1` / `./build.sh` already run both builds.

## Run it

- **Windows browser client:** unzip, open `OpenDavki` and double-click `OpenDavki.exe`.
- **Windows JavaFX client:** unzip, open `OpenDavkiDesktop` and double-click `OpenDavkiDesktop.exe`.
- **macOS:** open `OpenDavki.app` or `OpenDavkiDesktop.app`.
- **Linux:** run `OpenDavki/bin/OpenDavki` or `OpenDavkiDesktop/bin/OpenDavkiDesktop`.

The browser client starts the server, opens the default browser, and installs a tray icon with **Open** and **Quit**.
The JavaFX client opens a native window and offers File → Open in browser, File → Quit and View → Reload. If the desktop
has no tray, stop the browser client with Ctrl-C in the terminal that started it.

The browser executable accepts the normal commands:

```bash
OpenDavki paths
OpenDavki doctor
OpenDavki serve --no-open
OpenDavki import statement.csv -o ledger.json
```

On Windows the launchers are GUI executables, so command output is not printed; use the exit code or a terminal build
(`java -jar`) when you need to read it. `doctor` exits `0` when its checks pass. `OpenDavkiDesktop --smoke` runs a
non-interactive self-check (legacy page mounted, file-picker/download bridges present, one-row import completed).

## Where data lives

| Platform | Settings | Cache, rates, extracted XSDs |
|---|---|---|
| Windows | `%APPDATA%\OpenDavki\.env` | `%LOCALAPPDATA%\OpenDavki` |
| macOS | `~/Library/Application Support/OpenDavki/.env` | `~/Library/Caches/OpenDavki` |
| Linux | `~/.config/opendavki/.env` or `$XDG_CONFIG_HOME/opendavki/.env` | `~/.cache/opendavki` or `$XDG_CACHE_HOME/opendavki` |

`OPENDAVKI_HOME` puts everything under one directory for portable use. `opendavki paths` prints the effective paths.
Broker statements and ledgers are processed in memory; the server writes neither to disk. The only other writes are
explicit: **Save settings** updates the settings file, a settings-file upload copies the chosen file, and **Save
project** downloads a project file through the browser/native save dialog. Save the ledger JSON or generated XML files
yourself when you want to keep them.

Uninstalling means deleting the application folder. Delete the two user directories above as well if you want to remove
settings and caches.

## CI and verification status

The GitHub Actions matrix builds the browser and JavaFX clients for Windows x64, macOS x64, macOS arm64 and Linux x64.
The **Windows** builds are created and smoke-tested locally: the browser client starts with its bundled runtime,
`paths` extracts and verifies the four XSDs, `serve --no-open` returns the bundled interface, and the JavaFX client
mounts the legacy page, exercises the file-picker and download bridges and imports a one-row statement. CI has produced
the browser artifacts and each package's `paths` probe; the JavaFX macOS/Linux artifacts must be treated as
interaction-unverified until someone opens them on those desktops.

The e2e smoke test (`node frontend/e2e/smoke.cjs`) starts the self-contained browser jar without `--web-dir` or
`--schema-dir`. It runs in the CI test job after Playwright's Chromium is installed, and covers reload, settings save,
project save/open and a download.

## Known limitations

- **Unsigned binaries.** Windows SmartScreen shows "More info / Run anyway"; macOS Gatekeeper requires right-click Open
  or `xattr -dr com.apple.quarantine`. Code signing and Apple notarization need certificates and accounts the project
  does not have (BACKLOG U-05).
- **Portable app image only.** There is no MSI, DMG, deb, auto-update or desktop-menu installer yet; the artifact is a
  zip/tar.gz (BACKLOG U-06).
- **No custom icon.** `jpackage` uses its default icon until the maintainers choose a logo (BACKLOG U-08).
- **JavaFX Linux WebView is unverified.** The shell works on Windows, but JavaFX WebView's Linux native dependencies are
  platform-specific; the browser client remains the fallback (BACKLOG U-09).
- **JavaFX packaging is a fat jar.** The desktop artifact is larger (about 76 MB) because it includes JavaFX WebView and
  its native libraries. A jlink/jmod packaging pass could reduce it later.
- **Linux tray support varies by desktop.** If no tray is available, the browser launcher falls back to the terminal/Ctrl-C
  instructions.
- **macOS/Linux UI interaction is unverified.** CI starts the packaged executables and runs `paths`/the JavaFX smoke
  path, but nobody has opened the browser UI on those desktops; only Windows has the full local smoke test.
