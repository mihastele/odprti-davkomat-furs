# Tax engine (`si.opendavki.tax`)

`TaxEngine.compute(ledger, year, taxpayer)` turns a ledger into the Doh-KDVP and Doh-Div inputs for one tax year and
an audit trail. CLI: `opendavki report ledger.json --year 2024 --tax-number 12345678`.

> **Verification status:** tested only with synthetic cases whose expected values were worked out by hand. The rules
> marked *assumption* are not verified against ZDoh-2 or FURS instructions (see [BACKLOG.md](BACKLOG.md), T-xx, X-xx).
> eDavki computes the declared gains itself from the sheet rows; the gains we print are an audit cross-check.

## What it does

1. **Scope.** Entries after 31 December of the year are ignored. Only instruments with at least one sale in the year are
   processed, so unrelated old data cannot cause errors. Dividends of the year are always processed.
2. **One FIFO pool per instrument (ISIN)**, across all brokers, accounts and years. Lots are ordered by acquisition date.
   Sales consume the oldest lots first; every slice becomes a `LotMatch` (sale line, lot line, quantity, unit cost,
   unit proceeds, gain).
3. **EUR conversion** per entry with the rate of the transaction date from the configured source (`docs/fx.md`; weekends
   use the previous rate and say so). Every rate used is recorded (`RateUse`). Gains and unit values keep 12 decimals.
4. **Fees** (`OPENDAVKI_INCLUDE_FEES`, default `true`): the fee share per unit is added to the purchase cost and deducted
   from the sale value. *Assumption (T-01).*
5. **Splits** multiply lot quantities and divide unit costs. The same split reported by several brokers is applied once.
6. **Transfers.** An outgoing and an incoming transfer of the same quantity between different accounts within 10 days are
   paired and change nothing (lots keep their original date and cost). An unpaired incoming transfer needs acquisition
   date and cost basis (`SecurityTransfer.acquisitionDate/costBasisPerUnit`) or its lot has unknown cost and a sale that
   reaches it fails. An unpaired outgoing transfer removes the oldest lots and warns; it is not treated as a sale.
7. **Doh-KDVP sheet** (type `PLVP`, one per sold instrument): lots still open on 1 January (remaining quantity, original
   acquisition date and unit cost), lots that arrived in the year by transfer, the year's purchases and the year's sales,
   all restated into the units valid after the year's splits so the running stock (F8) stays consistent. The sheet's
   final stock is checked against the FIFO pool (`INTERNAL_INCONSISTENCY` if they differ). *Representation is an
   assumption (T-03).*
8. **Doh-Div**: each dividend converted at its payment date, gross and foreign tax separately, never netted with gains.
   Country = source country of the payment (withholding country, else ISIN prefix).

## Failure behaviour

The first error of an instrument excludes that instrument from the report (`INSTRUMENT_EXCLUDED`); the same for a
dividend (`DIVIDEND_EXCLUDED`). The result is then `complete = false` and the CLI refuses to write XML unless
`--allow-incomplete` is given. Errors: `MISSING_PURCHASE_HISTORY`, `SALE_USES_UNKNOWN_COST`, `UNSUPPORTED_CORPORATE_ACTION`,
`RATE_UNAVAILABLE`, `MISSING_ISIN`, `INVALID_ISIN`, `NEGATIVE_UNIT_VALUE`, `MISSING_COUNTRY`, `INTERNAL_INCONSISTENCY`.
Warnings: `TRANSFER_BASIS_MISSING`, `TRANSFER_OUT_UNPAIRED`, `PAYER_DETAILS_MISSING`, `NAME_TRUNCATED`.

## Outputs of `report`

`kdvp-<year>.xml`, `div-<year>.xml` (validated against the XSDs), `audit-<year>.json` with every lot match, every rate
used, every dividend and every diagnostic, and `audit-<year>.md`, the same trail as a readable report: status banner,
per-instrument summary, each sale with the lots it consumed and the statement line of each, dividends, every exchange
rate with its transaction date and rate date, and all errors and warnings. `opendavki audit audit-<year>.json` renders
the Markdown again from the JSON. Money is rounded to cents (`HALF_UP`) and unit values to 8 decimals for reading only;
the JSON keeps 12 decimals.

## Not covered

Other sheet types, F10, per-item foreign tax, loss transfer, time-of-day ordering, corporate actions other than forward
splits, payer address/tax number: see BACKLOG.
