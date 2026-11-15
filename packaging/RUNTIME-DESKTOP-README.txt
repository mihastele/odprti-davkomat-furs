OpenDavki Desktop
=================

This is the native-window build of OpenDavki. It contains the same Vue interface as the browser build, rendered by
JavaFX WebView in a desktop window, plus its own Java runtime. You do not need Java, Maven or Node.js.

Start it with OpenDavkiDesktop.exe. Nothing is uploaded: the local server listens on 127.0.0.1 only.

The window has File → Open in browser, File → Quit and View → Reload. Downloads (XML, audit, project files) use the
normal save dialog. File selection for statements, ledgers, projects and settings uses the native file picker, so it
works even where WebView's HTML file input does not.

The browser build (OpenDavki.exe) remains the reference client and is the fallback if a JavaFX WebView feature is
missing on this platform.

Settings and caches live in the same per-user directories as the browser build:
  %APPDATA%\OpenDavki        settings (.env)
  %LOCALAPPDATA%\OpenDavki   cache, downloaded rates, extracted official schemas
