OpenDavki
=========

This folder is a self-contained OpenDavki build. It includes its own Java runtime; you do not need Java,
Maven or Node.js.

Windows
-------

Double-click OpenDavki.exe. It starts a local-only server and opens the web interface in your default browser.
A tray icon lets you reopen the page or quit. The server listens on 127.0.0.1 only.

The first launch may show a Windows SmartScreen warning because this build is not code-signed. Choose "More info"
and "Run anyway" only if you downloaded it from the project's official release page.

Where your data lives
---------------------

Settings and cached exchange rates are kept under:

  %APPDATA%\OpenDavki        settings (.env)
  %LOCALAPPDATA%\OpenDavki   cache, downloaded rates, extracted official schemas

Nothing is uploaded. Broker statements are processed in memory; save the ledger JSON or the generated XML files
yourself when you want to keep them.

Command line
------------

The same executable accepts the usual commands, for example:

  OpenDavki.exe paths
  OpenDavki.exe doctor
  OpenDavki.exe serve --no-open

Uninstall
---------

Delete this folder. If you also want to remove settings and caches, delete the two OpenDavki folders listed above.
