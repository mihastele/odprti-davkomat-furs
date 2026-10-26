# OpenDavki

Open-source, local-first Slovenian investment tax reporting. Java backend, Vue.js frontend (later).

    broker statements -> normalized ledger -> Slovenian tax rules -> validated eDavki XML

Principle: never output a tax number that cannot be traced back to an original broker statement line.

## Status: ODV-001 (FURS XML foundation)

A CLI that generates Doh-KDVP and Doh-Div XML from synthetic JSON, validates against XSDs, and reports failures with line/column.

    cd backend && mvn package
    J="java -jar target/opendavki-backend-0.1.0-SNAPSHOT.jar"
    $J schemas status                          # which official XSDs are present
    $J generate KDVP ../examples/kdvp-synthetic.json -o kdvp.out.xml
    $J generate DIV  ../examples/div-synthetic.json  -o div.out.xml
    $J validate KDVP kdvp.out.xml

Exit codes: 0 valid, 1 XSD validation failed, 2 input error, 3 schema missing.

### Official schemas

The official FURS `Doh_KDVP_9.xsd`, `Doh_Div_3.xsd` and `EDP-Common-1.xsd` are in `schemas/` (checksums in
`SHA256SUMS`) and all tests validate against them. Writers follow their element order and constraints
(e.g. Doh_KDVP requires the five sheet counters and `edp:bodyContent`; Doh_Div must not have `bodyContent` and
carries `Dividend` rows as siblings of `Doh_Div`). Quantities and unit values are written with 8 decimals, the schema maximum.

Schema-valid is necessary, not sufficient: still import a generated file into eDavki and compare it with a
hand-prepared declaration before filing. Not yet generated: short/contract/share/capital-reduction sheets,
foreign-tax details per item, loss transfer, `F10` (ZDoh-2 art. 97 rule).

## Design rules

- `BigDecimal` only for money/quantities; HALF_UP at export.
- Dividends (Doh-Div) and capital gains (Doh-KDVP) are separate models; losses are never netted against dividends.
- Each model row carries `sourceRef` (statement file + line) for the audit trail; never exported to XML.
- A sale exceeding held quantity fails loudly (missing purchase history) instead of exporting.

## Roadmap

| | |
|---|---|
| ODV-001 | XML foundation (this) |
| ODV-002 | Canonical ledger + broker adapter SPI; one Maven module per broker so contributors never touch the tax engine |
| ODV-003 | IBKR importer (Flex/Activity CSV), regression fixtures with anonymized statements |
| ODV-004 | Cross-broker FIFO, splits, fractional shares, EUR conversion (ECB/Bank of Slovenia rates) |
| ODV-005 | Audit report + diagnostics (missing purchases, duplicates, suspicious FX) |
| ODV-006+ | Trading 212, Revolut, Trade Republic, eToro, XTB, DEGIRO, Lightyear, N26 |
| ODV-0xx | Vue 3 UI running against a local-only backend |

Broker availability for Slovenian residents is not guaranteed; listing means statement-import support only.
