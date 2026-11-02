# Web interface

A browser interface for the same steps as the command line: choose statements, check what was read, create the declarations.
It runs **only on your computer**: `opendavki serve` starts a small server on `127.0.0.1` and the page talks to nothing else.

```bash
npm --prefix frontend ci && npm --prefix frontend run build      # once (Node 22)
java -jar backend/cli/target/opendavki.jar serve                 # prints an address; open it in your browser
```

The address contains a random key after `#` (for example `http://127.0.0.1:41873/#token=...`). The browser never sends the
fragment to a server; the page keeps the key in memory and removes it from the address bar. Settings come from `.env`
exactly as for the command line; `--web-dir`, `--env-file`, `--schema-dir` and `--port` are available.

## What it does

1. **Statements**: pick files (CSV), a broker format (or detect it), optional broker profiles (`docs/profiles.md`), or open a
   ledger saved earlier. A file nobody recognises can be summarised with *Show the layout of the first file* (column names and
   value shapes only), which is what you paste into an issue to get your broker supported.
2. **Check**: findings (errors, warnings, notes) and every ledger entry with the statement line it came from; save the ledger.
3. **Declarations**: tax year and tax number, then Doh-KDVP and Doh-Div XML (each shown as valid or invalid against the official
   schema), the per-security cross-check, the audit report with every figure traced to its statement line, and downloads. An
   incomplete result produces no XML unless you tick the clearly marked box (inspection only).

## Security model

- Listens on the loopback address only. Every API call needs the per-start key; a wrong or missing key gets 401.
- Requests whose `Host` or `Origin` is not this server are refused (other websites and DNS rebinding cannot use it).
- Request bodies are limited to 64 MB; unknown JSON properties are rejected; static files are served only from the web directory.
- Responses carry `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` and a Content-Security-Policy of `default-src 'self'`.
- Nothing is written to disk by the server. The imported ledger is kept **in the server's memory** (the latest five) and the page
  refers to it by id; amounts reach the page as text, never as JSON numbers, so no value passes through a JavaScript double.
  Restarting the server forgets everything; save the ledger JSON if you want to keep it.
- Statement text shown in the page (names, descriptions) is rendered as text, never as HTML.

## Tests

`npm --prefix frontend test` (unit tests), the Java `WebServerTest` (API and security behaviour) and, with Playwright's Chromium
installed, `node frontend/e2e/smoke.cjs` (drives the real page against the real server, including a download and an
unrecognised file).
