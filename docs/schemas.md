# Official FURS schemas

`schemas/` holds `Doh_KDVP_9.xsd`, `Doh_Div_3.xsd` and `EDP-Common-1.xsd` exactly as published by FURS, with their checksums in
`schemas/SHA256SUMS`.

- `opendavki schemas status` verifies every file against `SHA256SUMS` (OK / MISMATCH / MISSING) and exits 3 on any problem;
  a unit test does the same on every build, so an edited schema fails the build.
- `opendavki schemas fetch <name> <https-url>` refuses a download whose checksum differs from the one recorded for that name
  and warns when the name is not listed yet.

## When FURS publishes a new version

The writers produce the element layout of the versions above; a different version can change the layout, so it is never picked
up automatically. To adopt one:

1. Download the new XSD (and any new `EDP-Common`) from the eDavki XML schema page into `schemas/` under its own file name.
2. Add its line to `SHA256SUMS`.
3. Change `FormType` (file name, namespace) and `KdvpXmlWriter` / `DivXmlWriter` to the new structure; the tests validate the
   output against the schemas and will show every difference.
4. Keep the old file only if it is still needed; update this page and the README.
