# Official FURS schemas

`schemas/` holds the official XSD collection exactly as published by FURS, with checksums in `schemas/SHA256SUMS`. The
application writes Doh-KDVP, Doh-Div and Doh-Obr, whose four needed files are `Doh_KDVP_9.xsd`, `Doh_Div_3.xsd`,
`Doh_Obr_2.xsd` and the imported `EDP-Common-1.xsd`.

- The same four files and the official checksum file are packaged inside the jar. On first use they are extracted to the
  per-user cache, verified, and a filtered `SHA256SUMS` covering exactly those files is written next to them. A cache
  that already matches is reused; a modified one is replaced.
- `opendavki schemas status` prints the active directory and verifies every listed file against `SHA256SUMS`
  (OK / MISMATCH / MISSING); it exits 3 on any problem. A unit test does the same on every build, so an edited schema
  fails the build.
- `opendavki schemas fetch <name> <https-url>` refuses a download whose checksum differs from the one recorded for that name
  and warns when the name is not listed yet. It writes to the active directory (the checkout `schemas/`, the environment
  override, or the user cache).

## When FURS publishes a new version

The writers produce the element layout of the versions above; a different version can change the layout, so it is never picked
up automatically. To adopt one:

1. Download the new XSD (and any new `EDP-Common`) from the eDavki XML schema page into `schemas/` under its own file name.
2. Add its line to `SHA256SUMS`.
3. Change `FormType` (file name, namespace) and `KdvpXmlWriter` / `DivXmlWriter` to the new structure; the tests validate the
   output against the schemas and will show every difference.
4. Keep the old file only if it is still needed; update this page and the README.
