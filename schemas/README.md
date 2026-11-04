# Official FURS eDavki schemas

The complete collection of XSD files from the eDavki XML schema page, as supplied by the maintainer on 2026-11-04 and stored
byte-for-byte. Their SHA-256 sums are in `SHA256SUMS`. Never edit them; `opendavki schemas status` and a unit test fail if any
file differs from its recorded checksum.

Forms the tool generates (latest version of each in this collection): `Doh_KDVP_9.xsd`, `Doh_Div_3.xsd`, `Doh_Obr_2.xsd`, all
importing `EDP-Common-1.xsd`. A test fails when a newer version of one of them is added here, because the writers must then be
adapted (docs/schemas.md).
