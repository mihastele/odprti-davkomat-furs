# OpenDavki

Open-source, local-first Slovenian investment tax reporting. Java backend, Vue.js frontend (later).

    broker statements -> normalized ledger -> Slovenian tax rules -> validated eDavki XML

Principle: never output a tax number that cannot be traced back to an original broker statement line.

## Status

**ODV-001 done:** Doh-KDVP (all six sheet types) and Doh-Div (incl. corporate-payout and late-filing blocks) XML generation, validated against the official XSDs.
**ODV-002 done:** canonical ledger, broker adapter SPI with a no-lost-rows contract, generic CSV adapter, ledger checks.

    cd backend && mvn install                  # Java 21
    J="java -jar cli/target/opendavki.jar"
    $J adapters                                # adapters on the classpath
    $J import statement.csv -o ledger.json     # read, de-duplicate, check (exit 1 on errors)
    $J check ledger.json
    $J schemas status --schema-dir ../schemas
    $J generate KDVP ../examples/kdvp-synthetic.json --schema-dir ../schemas -o kdvp.out.xml
    $J generate DIV  ../examples/div-synthetic.json  --schema-dir ../schemas -o div.out.xml
    $J validate KDVP kdvp.out.xml --schema-dir ../schemas

Exit codes: 0 ok, 1 XSD validation failed / ledger errors, 2 input error, 3 schema missing.

Layout: `backend/core` (ledger, adapter SPI, FURS XML), `backend/brokers/<id>` (one module per broker, see
[docs/adapters.md](docs/adapters.md)), `backend/cli`. Generic CSV: [docs/generic-csv.md](docs/generic-csv.md).

### Official schemas

The official FURS `Doh_KDVP_9.xsd`, `Doh_Div_3.xsd` and `EDP-Common-1.xsd` are in `schemas/` (checksums in
`SHA256SUMS`) and all tests validate against them. Writers follow their element order and constraints
(e.g. Doh_KDVP requires the five sheet counters and `edp:bodyContent`; Doh_Div must not have `bodyContent` and
carries `Dividend` rows as siblings of `Doh_Div`). Quantities and unit values are written with 8 decimals, the schema maximum.

Schema-valid is necessary, not sufficient: still import a generated file into eDavki and compare it with a
hand-prepared declaration before filing. Constructs a sheet type cannot express (e.g. F10/F11 on a short sheet) are
rejected rather than dropped. The report models take amounts as given: FIFO matching and EUR conversion (ODV-004)
will produce them from the ledger.

## Design rules

- `BigDecimal` only for money/quantities; HALF_UP at export.
- Dividends (Doh-Div) and capital gains (Doh-KDVP) are separate models; losses are never netted against dividends.
- Each model row carries `sourceRef` (statement file + line) for the audit trail; never exported to XML.
- A sale exceeding held quantity fails loudly (missing purchase history) instead of exporting.

## Roadmap

| | |
|---|---|
| ODV-001 | XML foundation (done) |
| ODV-002 | Canonical ledger + broker adapter SPI, one Maven module per broker (done) |
| ODV-003 | IBKR importer (Flex/Activity CSV), regression fixtures with anonymized statements |
| ODV-004 | Cross-broker FIFO, splits, fractional shares, EUR conversion (ECB/Bank of Slovenia rates) |
| ODV-005 | Audit report + diagnostics (missing purchases, duplicates, suspicious FX) |
| ODV-006+ | Trading 212, Revolut, Trade Republic, eToro, XTB, DEGIRO, Lightyear, N26 |
| ODV-0xx | Vue 3 UI running against a local-only backend |

Broker availability for Slovenian residents is not guaranteed; listing means statement-import support only.
