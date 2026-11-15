# Desktop application

The desktop application is the same local web interface, packaged with its own Java runtime, the Vue build and the
official XSDs. It does not need Java, Maven or Node.js on the user's machine, and it does not listen on anything except
`127.0.0.1`.

## Build an app image

From the repository root, on the platform you are packaging for:

```bash
./packaging/package.ps1          # Windows; produces target/package/OpenDavki-<version>-windows-<arch>.zip
./packaging/package.sh           # macOS/Linux; zip on macOS, tar.gz on Linux
```

The scripts run the one-command source build first, verify that the jar really contains `web/index.html` and the
bundled XSDs, create a `jlink` runtime with `jpackage`, start the packaged launcher once with `paths` from outside the
repository, and write a `SHA256SUMS` file. The app bundle metadata uses version `1.0.0` because macOS rejects a leading
zero in `--app-version`; the artifact filenames and the project version remain `0.1.0`.

`-SkipBuild` / `--skip-build` packages an existing build. The build is not code-signed; see the limitations below.

## Run it

- **Windows:** unzip, open the `OpenDavki` folder and double-click `OpenDavki.exe`.
- **macOS:** unzip and open `OpenDavki.app`.
- **Linux:** extract the tar.gz and run `OpenDavki/bin/OpenDavki`.

With no arguments the launcher starts the server, opens the default browser, and installs a tray icon with **Open** and
**Quit**. If the desktop has no tray, stop it with Ctrl-C in the terminal that started it. The same executable accepts
the normal commands:

```bash
OpenDavki paths
OpenDavki doctor
OpenDavki serve --no-open
OpenDavki import statement.csv -o ledger.json
```

On Windows the launcher is a GUI executable, so command output is not printed; use the exit code or a terminal build
(`java -jar`) when you need to read it. `doctor` exits `0` when its checks pass.

## Where data lives

| Platform | Settings | Cache, rates, extracted XSDs |
|---|---|---|
| Windows | `%APPDATA%\OpenDavki\.env` | `%LOCALAPPDATA%\OpenDavki` |
| macOS | `~/Library/Application Support/OpenDavki/.env` | `~/Library/Caches/OpenDavki` |
| Linux | `~/.config/opendavki/.env` or `$XDG_CONFIG_HOME/opendavki/.env` | `~/.cache/opendavki` or `$XDG_CACHE_HOME/opendavki` |

`OPENDAVKI_HOME` puts everything under one directory for portable use. `opendavki paths` prints the effective paths.
Broker statements and ledgers are processed in memory; the server writes neither to disk. Save the ledger JSON or the
generated XML files yourself when you want to keep them.

Uninstalling means deleting the application folder. Delete the two user directories above as well if you want to remove
settings and caches.

## CI and verification status

The GitHub Actions matrix builds and packages Windows x64, macOS x64, macOS arm64 and Linux x64. The **Windows** build
has been created and smoke-tested locally: the launcher starts with its bundled runtime, `paths` extracts and verifies
the four XSDs, and `serve --no-open` returns the bundled interface. The macOS and Linux artifacts have not been run on
this machine; they are built by CI and must be treated as unverified until someone launches them and says so.

The e2e smoke test (`node frontend/e2e/smoke.cjs`) starts the self-contained jar without `--web-dir` or
`--schema-dir`. It runs in the CI test job after Playwright's Chromium is installed, and includes a page reload.

## Known limitations

- **Unsigned binaries.** Windows SmartScreen shows "More info / Run anyway"; macOS Gatekeeper requires right-click Open
  or `xattr -dr com.apple.quarantine`. Code signing and Apple notarization need certificates and accounts the project
  does not have (BACKLOG U-05).
- **Portable app image only.** There is no MSI, DMG, deb, auto-update or desktop-menu installer yet; the artifact is a
  zip/tar.gz (BACKLOG U-06).
- **No custom icon.** `jpackage` uses its default icon until the maintainers choose a logo (BACKLOG U-08).
- **Settings are still edited in `.env`.** The in-app settings screen and opt-in project persistence are ODV-012
  (BACKLOG U-02, U-04).
- **The JavaFX shell is not built yet.** This application uses the browser. A JavaFX WebView window on the same
  UI/server is planned as ODV-013; JavaFX 21's WebView has no ES-module support, so that shell needs a legacy Vite
  bundle and a download bridge, and Linux WebView support must be validated separately.
- **Linux tray support varies by desktop.** If no tray is available, the launcher falls back to the terminal/Ctrl-C
  instructions.
