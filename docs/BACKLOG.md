# Limitations and backlog

Everything known to be missing, simplified or unverified. Nothing here is hidden behaviour: where the code can detect
the situation it reports a diagnostic or refuses to continue. Items are grouped by area; `Planned` names the ticket
that is meant to close it (tickets beyond ODV-003 are provisional). Update this file whenever a gap is found or closed.

**Housekeeping rule:** when a gap is fixed, delete its row here *and* its line in the README's "Known limitations"
section in the same change. When a new gap is found, add it to both.

**Verification summary:** XML output is validated against the official XSDs (`schemas/`) but has never been imported
into eDavki. No importer has been run on a real broker statement. No tax number is computed yet.

## Declarations (ODV-001)

| ID | Gap | Planned |
|---|---|---|
| D-01 | Generated Doh-KDVP/Doh-Div files have never been imported into eDavki or compared with a hand-prepared declaration. Schema-valid ≠ accepted ≠ correct. | ODV-007 (golden files) |
| D-02 | Report models take amounts as given. No FIFO, no EUR conversion, no fee-in-cost-basis, no gain/loss totals. | ODV-004 |
| D-03 | Only the schema versions `Doh_KDVP_9`, `Doh_Div_3`, `EDP-Common-1` are known; `FormType` hard-codes names/namespaces. No version detection or update procedure when FURS revises them. | ODV-007 |
| D-04 | Only the original filing is produced (`DocumentWorkflowID` = `O`). Corrections, self-reports and late submissions are only partly modelled (Doh-Div `selfReport`, `SubseqSubmission` pass-through). | ODV-007 |
| D-05 | No semantic validation beyond the XSD: acquisition-type (F2) rules, F10/F11/F5 applicability, `HasLossTransfer` vs `TaxBaseDecrease` consistency, loss carry-over, field length limits are only caught by the schema (or not at all). | ODV-005 |
| D-06 | Country handling is raw: KDVP wants 3-digit FURS country codes, Doh-Div free-text ISO alpha-2. No code tables or lookup. | ODV-004 |
| D-07 | Same-day ordering: purchases are placed before sales because the model has only dates, not times. True FIFO order within a day may differ. | ODV-004 |
| D-08 | `F8` (running stock) starts at zero for the sheet: earlier years' purchases must be included in the input, otherwise sales fail with "exceeds held quantity". No automatic carry-forward of historic lots. | ODV-004 |
| D-09 | Not generated: other eDavki forms (interest Doh-Obr, derivatives/CFD, crypto, rent, ...). Only the sheets inside Doh-KDVP/Doh-Div that the models cover. | ODV-0xx |
| D-10 | Non-resident filers, partial-year periods and exemption claims (`Remission`) are modelled but untested against real cases. | ODV-007 |
| D-11 | No signing or submission to eDavki; the output is an import file only. Intentional. | — |
| D-12 | `generate` takes a hand-written report JSON. It will be replaced by the pipeline ledger → tax engine → report. | ODV-004 |
| D-13 | `schemas fetch` downloads over HTTPS but does not verify the file against `schemas/SHA256SUMS`; `schemas status` only prints hashes. | ODV-007 |

## Ledger and adapter framework (ODV-002)

| ID | Gap | Planned |
|---|---|---|
| L-01 | No FX data source or EUR conversion; the ledger keeps original currencies. Rate provenance (ECB / Bank of Slovenia) is undecided. | ODV-004 |
| L-02 | Fees are stored but not applied to cost basis or proceeds. | ODV-004 |
| L-03 | `LedgerChecker` only checks per-account positions, ISIN presence and unsupported corporate actions. Missing: transfer OUT↔IN pairing across brokers, duplicate detection for entries without ids across brokers, suspicious FX/price checks, quantity-precision checks, wash-trade hints. | ODV-005 |
| L-04 | Corporate actions: only forward splits change positions. Reverse splits, mergers, spin-offs, ISIN changes, stock dividends, return of capital, rights are carried as `UNSUPPORTED` and need manual handling. | ODV-006 |
| L-05 | No instrument master: renamed symbols or changed ISINs are not linked; instruments without ISIN match by symbol only. | ODV-006 |
| L-06 | No cash, interest, fee or FX entries in the ledger (only trades, dividends, corporate actions, transfers). | ODV-0xx |
| L-07 | Ledger JSON `formatVersion` is 1 with no migration path. Entries from `import` are written even when errors exist (exit code 1 signals it). | ODV-005 |
| L-08 | Generic CSV: no decimal comma, no alternative date formats, no time-of-day. | when needed |
| L-09 | Statement hashes use raw bytes; fixtures depend on LF line endings (`.gitattributes` enforces it for `*.csv`/`*.json`). | — |
| L-10 | No CI configuration (build + tests on push) and no LICENSE file yet. | ODV-008 |
| L-11 | Frontend (Vue 3) and the local-only server it needs are not started. | ODV-009 |

## Interactive Brokers adapter (ODV-003)

| ID | Gap | Planned |
|---|---|---|
| I-01 | Only Activity Statement **CSV**. Flex Query, PDF, HTML, XML unsupported. | ODV-003b |
| I-02 | **Never run on a real statement.** Section/column names come from IBKR's documented layout; the fixture is synthetic. Real files may differ (extra discriminators, multi-account layouts, renamed columns). Anonymized real samples are needed as regression fixtures. | first available sample |
| I-03 | Execution rows (`DataDiscriminator` = `Trade`) are skipped when `Order` rows exist; if a real statement differs, trades could be missed or doubled. | with I-02 |
| I-04 | Options, futures, bonds, CFDs, warrants, structured products are skipped with a WARNING (separate tax treatment, not implemented). Forex conversions are ignored. | ODV-0xx |
| I-05 | Reverse splits and all non-forward-split corporate actions become `UNSUPPORTED` entries. | ODV-006 |
| I-06 | Withholding tax is joined to dividends only by instrument + date + currency. Late reclassifications/refunds fail loudly (`WHT_WITHOUT_DIVIDEND`, `WHT_NET_REFUND`); negative dividend reversals fail (`NEGATIVE_DIVIDEND`). | with I-02 |
| I-07 | Dividend source country falls back to the ISIN prefix, which is the country of issue, not necessarily the paying country. | with I-02 |
| I-08 | Incoming transfers have no cost basis or acquisition date (not in the statement); they must be supplied from the sending broker or cross-broker FIFO cannot be exact. A mechanism for user-supplied overrides does not exist. | ODV-004 |
| I-09 | Several accounts in one statement: all entries use the first account number (WARNING `MULTIPLE_ACCOUNTS`). | with I-02 |
| I-10 | Interest, securities lending and grant activity are skipped with a WARNING (they belong to other forms). | ODV-0xx |
| I-11 | Time of day is dropped; the trade date, not the settlement date, is used. | ODV-004 (D-07) |
| I-12 | US-entity statements identify securities by CUSIP, not ISIN: those instruments stay without ISIN (`MISSING_ISIN`). No CUSIP→ISIN mapping. | ODV-006 |
| I-13 | Trades flagged cancelled (`Ca`) are mapped with a WARNING; matching cancel/rebook pairs are not netted automatically. | with I-02 |
| I-14 | Payment-in-lieu is treated as a dividend with a WARNING; its Slovenian tax treatment is unconfirmed. | ODV-005 |
| I-15 | IBKR's own `Realized P/L` and `Basis` columns are ignored; they could serve as an independent cross-check of our FIFO result. | ODV-005 |

## Exchange rates (ODV-004 groundwork)

| ID | Gap | Planned |
|---|---|---|
| X-01 | The default source (ECB) is **not confirmed as FURS's recommendation**; FURS guidance could not be read from the build environment. | immediately, by a maintainer |
| X-02 | `BSI` has no default URL: no official Bank of Slovenia download endpoint/format was confirmed. It only reads a URL you configure (ECB `Cube` XML or CSV). | ODV-004 |
| X-03 | The ECB download (`eurofxref-hist.xml`) was never exercised live (network blocked in the build environment); only a local sample in the same layout is tested. | ODV-004 |
| X-04 | Which rate date applies (transaction day, previous day, monthly) and how/when to round is undecided and unverified. Current behaviour: rate of the transaction day, else the latest earlier one within the lookback limit; 12-decimal conversion, no rounding. | ODV-004 |
| X-05 | The "third source" is a user-supplied CSV (`FILE`), chosen as an assumption (ECB and BSI were the two sources discussed); replace if a different third source was intended. | decision needed |
| X-06 | Rates are not yet wired into the tax pipeline: nothing converts ledger amounts to EUR, and the audit trail does not list rates used. | ODV-004 |
