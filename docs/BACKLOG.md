# Limitations and backlog

Everything known to be missing, simplified or unverified. Nothing here is hidden behaviour: where the code can detect
the situation it reports a diagnostic or refuses to continue. Items are grouped by area; `Planned` names the ticket
that is meant to close it (tickets beyond ODV-003 are provisional). Update this file whenever a gap is found or closed.

**Housekeeping rule:** when a gap is fixed, delete its row here *and* its line in the README's "Known limitations"
section in the same change. When a new gap is found, add it to both.

**Verification summary:** XML output is validated against the official XSDs (`schemas/`) but has never been imported
into eDavki. No importer has been run on a real broker statement. The tax engine computes gains and unit values, but only synthetic cases checked by hand have been run through it; none of its output has been compared with a real declaration or eDavki's own calculation.

## Declarations (ODV-001)

| ID | Gap | Planned |
|---|---|---|
| D-01 | Generated Doh-KDVP/Doh-Div files have never been imported into eDavki or compared with a hand-prepared declaration. Schema-valid ≠ accepted ≠ correct. | ODV-007 (golden files) |
| D-03 | Only the schema versions `Doh_KDVP_9`, `Doh_Div_3`, `EDP-Common-1` are known; `FormType` hard-codes names/namespaces. No version detection or update procedure when FURS revises them. | ODV-007 |
| D-04 | Only the original filing is produced (`DocumentWorkflowID` = `O`). Corrections, self-reports and late submissions are only partly modelled (Doh-Div `selfReport`, `SubseqSubmission` pass-through). | ODV-007 |
| D-05 | No semantic validation beyond the XSD: acquisition-type (F2) rules, F10/F11/F5 applicability, `HasLossTransfer` vs `TaxBaseDecrease` consistency, loss carry-over, field length limits are only caught by the schema (or not at all). | ODV-005 |
| D-06 | Country handling is raw: KDVP wants 3-digit FURS country codes, Doh-Div free-text ISO alpha-2. No code tables or lookup. | ODV-004 |
| D-07 | Same-day ordering: purchases are placed before sales because the model has only dates, not times. True FIFO order within a day may differ. | ODV-004 |
| D-09 | Not generated: other eDavki forms (interest Doh-Obr, derivatives/CFD, crypto, rent, ...). Only the sheets inside Doh-KDVP/Doh-Div that the models cover. | ODV-0xx |
| D-10 | Non-resident filers, partial-year periods and exemption claims (`Remission`) are modelled but untested against real cases. | ODV-007 |
| D-11 | No signing or submission to eDavki; the output is an import file only. Intentional. | — |
| D-13 | `schemas fetch` downloads over HTTPS but does not verify the file against `schemas/SHA256SUMS`; `schemas status` only prints hashes. | ODV-007 |

## Ledger and adapter framework (ODV-002)

| ID | Gap | Planned |
|---|---|---|
| L-03 | `LedgerChecker` does not yet detect: duplicate entries across brokers or files that carry no ids and differ only in formatting, implausible exchange rates (day-over-day jumps), wash-trade patterns, or dividends on instruments with no position. | ODV-005 |
| L-12 | The `SUSPICIOUS_PRICE` thresholds (4× the median split-adjusted price, at least 3 trades) and the 10-day transfer pairing window are heuristics chosen without data; they may miss real errors or flag normal moves. | ODV-005 |
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
| I-08 | Incoming IBKR transfers carry no cost basis or acquisition date. Transfers between two imported accounts are paired automatically (T-04); for a sender that is not imported, the basis can only be added by editing the ledger JSON. No override mechanism exists. | ODV-005 |
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
| X-02 | `BSI` has no default URL (the maintainers state FURS points to the ECB rates as published by Banka Slovenije, so a working BSI download would be the most natural source): no official Bank of Slovenia download endpoint/format was confirmed. It only reads a URL you configure (ECB `Cube` XML or CSV). | ODV-004 |
| X-03 | The ECB download (`eurofxref-hist.xml`) was never exercised live (network blocked in the build environment); only a local sample in the same layout is tested. | ODV-007 |
| X-04 | Which rate date applies (transaction day, previous day, monthly) and how/when to round is undecided and unverified; only the choice of source (ECB reference rates) is confirmed by the maintainers. Current behaviour (now used by the tax engine): rate of the transaction day, else the latest earlier one within the lookback limit; 12-decimal conversion, unit values rounded to 8 decimals only when written. | maintainer decision |
| X-05 | The "third source" is a user-supplied CSV (`FILE`), chosen as an assumption (ECB and BSI were the two sources discussed); replace if a different third source was intended. | decision needed |

## Tax engine (ODV-004)

| ID | Gap | Planned |
|---|---|---|
| T-01 | Fees are added to purchase cost and deducted from sale value by default (`OPENDAVKI_INCLUDE_FEES`). Not verified against ZDoh-2 / FURS instructions. | ODV-007 |
| T-02 | Verified only on synthetic cases computed by hand; never compared with a real broker statement, a real filed declaration, or eDavki's own gain calculation. | ODV-007 |
| T-03 | Sheet representation is an assumption: lots open on 1 January appear as purchase rows with their *remaining* quantity and original date/cost, prior-year sales are omitted, and in-year splits restate all rows into post-split units. Not confirmed to be what FURS expects. | ODV-007 |
| T-04 | Transfer pairing is a heuristic (same quantity, different account, within 10 days). Unpaired outgoing transfers are assumed to leave the portfolio without a taxable event. | ODV-005 |
| T-05 | An unsupported corporate action (anything but a forward split) excludes the instrument from the report; there is no manual override or correction mechanism. | ODV-006 |
| T-06 | Doh-Div rows lack payer address and tax number (not in broker statements); payer country is the source-country proxy; dividend type is always "1". | ODV-005 |
| T-07 | Only `PLVP` sheets are produced for sold instruments. No short/contract/share/capital-reduction sheets, no F10, no per-item foreign tax, no loss transfer; `HasForeignTax` is always false. | ODV-0xx |
| T-08 | `IsFond` is true for ETFs and funds, false for other instruments. Assumption. | ODV-007 |
| T-09 | Unit values are written with 8 decimals (schema maximum), so quantity × unit value can differ from the exact total by fractions of a cent (e.g. splits or fee shares that do not divide evenly). | ODV-007 |
| T-10 | The first error of an instrument stops processing it, so several independent problems in one instrument need several runs. Prior-year sales of an instrument only matter if it also sells in the report year. | ODV-005 |
| T-11 | The engine has no wash-sale, 'holding period' or loss-restriction rules and no per-year loss carry-over; eDavki/FURS rules for those are not modelled. | ODV-0xx |

## Audit report (ODV-005)

| ID | Gap | Planned |
|---|---|---|
| A-01 | The audit report is Markdown only and English only; no HTML/PDF, no localisation. | ODV-009 |
| A-02 | The report itemises sales and the lots they consumed, but not the Doh-KDVP rows of lots that stay open (carried into the year or bought and unsold), nor a row-by-row reconciliation of the XML to source lines. | ODV-005 |
| A-03 | INFO diagnostics (paired transfers, rate fallbacks, split duplicates) are only counted in the Markdown, not listed; the JSON has them. | ODV-005 |

## Sample data

| ID | Gap | Planned |
|---|---|---|
| S-01 | No real or officially documented sample statement exists for any broker. Every importer is written from published layouts and tested on synthetic fixtures; see docs/sample-data.md for how to obtain verified samples (paper/demo accounts, anonymised donations, open-source test files). | ongoing |
