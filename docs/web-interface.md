# Web interface

A browser interface for the same steps as the command line: choose statements, check what was read, create the
declarations. It runs **only on your computer**: the server listens on `127.0.0.1` and the page talks to nothing else.

## Starting it

The packaged application starts this interface when you launch `OpenDavki` with no arguments. From a checkout:

```bash
./run.ps1                 # Windows: start the interface and open the browser
./run.sh                  # macOS/Linux
```

`java -jar backend/cli/target/opendavki.jar` does the same from any directory. The explicit form is still available:

```bash
java -jar backend/cli/target/opendavki.jar serve --no-open
```

`serve` prints the keyed address; add `--open` to open it, or `--tray` to keep Open/Quit in the system tray. The
address contains a random key after `#` (for example `http://127.0.0.1:41873/#token=...`). The browser never sends the
fragment to a server; the page keeps the key in `sessionStorage` (so a reload does not break it) and removes it from the
address bar. `sessionStorage` disappears when the tab closes.

The installed build has the web interface and the official XSDs **inside the jar**. `--web-dir` and `--schema-dir`
remain as development overrides. Build the interface once if you are working on it:

```bash
npm --prefix frontend ci && npm --prefix frontend run build
```

## What it does

1. **Statements**: pick files (CSV), a broker format (or detect it), optional broker profiles (`docs/profiles.md`), or open a
   ledger saved earlier. A file nobody recognises can be summarised with *Show the layout of the first file* (column names and
   value shapes only), which is what you paste into an issue to get your broker supported.
2. **Check**: findings (errors, warnings, notes) and every ledger entry with the statement line it came from; save the ledger.
   The ledger id is kept in the browser session, so reloading the page returns to this step while the server is still running.
3. **Declarations**: tax year and tax number, then Doh-KDVP and Doh-Div XML (each shown as valid or invalid against the official
   schema), the per-security cross-check, the audit report with every figure traced to its statement line, and downloads. An
   incomplete result produces no XML unless you tick the clearly marked box (inspection only).

**Save project** writes an explicit `opendavki-<year>.opendavki.json` containing the ledger and the year/tax number (the
tax number only when the box is ticked). **Open a saved project** restores it and prefills the report fields; the normal
ledger JSON remains supported too. The project format is versioned (`formatVersion` 1) and is validated on open.

The footer shows the settings file and schema directory actually in use; `opendavki paths` prints the same information in
a terminal. The interface can be switched between English and Slovenian; Java diagnostics remain in English (BACKLOG U-01).

## Settings and data

Settings come from the same resolution order as the command line: `--env-file`, then `OPENDAVKI_ENV_FILE`, then `./.env`,
then the per-user config directory (`%APPDATA%\OpenDavki\.env`, `~/Library/Application Support/OpenDavki/.env`, or
`~/.config/opendavki/.env`). `OPENDAVKI_HOME` selects a portable layout.

The **Settings** button edits the exchange-rate source and fallback, fees, time zone, interest code and the
instrument/override/rate paths. It can also copy an instruments CSV, overrides CSV, rates CSV or broker profile into the
settings directory. Nothing is written until **Save settings** (or the file's own **Save file** button) is pressed;
the response shows the absolute path. Values overridden by real process environment variables are marked and cannot be
changed from the page.

The official XSDs are extracted from the jar into the per-user cache on first use and verified against `SHA256SUMS`.
Downloaded exchange-rate tables use the same cache.

## Security model

- Listens on the loopback address only. Every API call needs the per-start key; a wrong or missing key gets 401.
- Requests whose `Host` or `Origin` is not this server are refused (other websites and DNS rebinding cannot use it).
- Request bodies are limited to 64 MB; unknown JSON properties are rejected; static files are served only from the
  configured web directory or the bundled `/web` resources.
- Responses carry `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and a Content-Security-Policy of
  `default-src 'self'`.
- The server keeps the latest five imported ledgers **in memory** and writes no statement or ledger to disk. The only
  writes are explicit: **Save settings** updates the settings file, a settings-file upload copies the chosen file, the
  CLI extracts the bundled schemas, and exchange-rate downloads may populate the cache. Project files are downloaded by
  the browser, not stored by the server. Restarting the server forgets the ledgers, so save a project or ledger JSON if
  you want to keep one.
- Statement text shown in the page (names, descriptions) is rendered as text, never as HTML.

## Tests

`npm --prefix frontend test` (unit tests), the Java `WebServerTest` (API, static files, security behaviour) and
`node frontend/e2e/smoke.cjs` (drives the real page against the real server, including a reload, settings save, project
save/open, a download and an unrecognised file). The CI test job installs Playwright's Chromium and runs the e2e check;
locally, install it with `npx --prefix frontend playwright install chromium`. The e2e check uses the self-contained jar:
no `--web-dir` or `--schema-dir`, and a throw-away settings file so it never edits the repository example.
