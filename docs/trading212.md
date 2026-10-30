# Trading 212 adapter (`trading212`)

Reads the **History export (CSV)** from Trading 212 (History → Export). One file covers at most one year; import several
files together with `opendavki import a.csv b.csv -o ledger.json` (overlaps are de-duplicated by the export's order ids).
Other formats (PDF statements, API) are not supported.

> **Verification status.** The layout and the arithmetic below were checked against one real export (217 rows, one EUR
> account, January to April 2026, stocks and ETFs on several exchanges). All 217 rows were accounted for, and for every one
> of the 205 trades and 11 dividends Trading 212's own `Total` agreed with the adapter's reading. That file is *not* in the
> repository (it is real personal data); `backend/brokers/trading212/src/test/resources/fixtures/history-basic.csv` is a
> synthetic file with the same structure and quirks. Tax outcomes (FIFO, rates, fees) are as unverified as for any broker,
> see [tax-engine.md](tax-engine.md).

## Columns

Looked up by name, so order and extra columns do not matter: `Action`, `Time (UTC)` (or `Time`), `ISIN`, `Ticker`, `Name`,
`Notes`, `ID`, `No. of shares`, `Price / share`, `Currency (Price / share)`, `Exchange rate`, `Result`, `Currency (Result)`,
`Total`, `Currency (Total)`, `Withholding tax`, `Currency (Withholding tax)`, `Currency conversion fee`,
`Currency (Currency conversion fee)`.

## Mapping

| Action | Result |
|---|---|
| `Market/Limit/Stop/Stop limit buy/sell` | `Trade`. Price and currency stay as in the export (the tax engine converts with the configured rate source, not Trading 212's rate); fee = `Currency conversion fee`; id = the export's `ID`. |
| `Dividend (Dividend)`, `Dividend (Ordinary)` | `DividendPayment`: gross = shares × `Price / share` (dividend per share) in the dividend currency, `Withholding tax` in the same currency, source country = ISIN prefix. |
| `Dividend (Dividend manufactured payment)` | same, plus WARNING `PAYMENT_IN_LIEU` (paid instead of a dividend on lent shares). |
| other `Dividend (...)` (return of capital, bonus, ...) | skipped, WARNING `UNSUPPORTED_DIVIDEND_TYPE` |
| `Deposit`, `Withdrawal`, `Currency conversion`, `Card debit/credit` | skipped, INFO `CASH_MOVEMENT` |
| anything with `interest` in the name | skipped, WARNING `UNSUPPORTED_ACTION` (other tax form) |
| anything with `split` in the name | `CorporateAction` UNSUPPORTED (the export has no ratio); the tax engine then excludes a sold instrument |
| any other action | skipped, WARNING `UNKNOWN_ACTION` |

Semantics found in the real file:

- **Trades.** `Exchange rate` is price-currency units per EUR. `Total` (account currency) is shares × price / rate **plus**
  the conversion fee for buys and **minus** it for sells. The adapter recomputes this as a plausibility check and raises
  WARNING `TOTAL_MISMATCH` if it differs by more than 0.02; it never changes the entry.
- **Pence.** `GBX` prices are pence; the adapter converts to `GBP` (÷ 100) because ECB publishes GBP.
- **Dividends.** `Exchange rate` is EUR per dividend-currency unit (the inverse of the trade convention); `Total` is the gross
  dividend in EUR rounded to cents (checked within 0.01, otherwise WARNING `TOTAL_MISMATCH`). `Withholding tax` is in the
  dividend currency and appears rounded **up** to cents; a value of `-0.00` is zero. A non-zero negative value or a
  different currency fails the row (`NEGATIVE_WITHHOLDING`, `WITHHOLDING_CURRENCY`).
- **Instruments** are identified by ISIN. One ISIN can appear under several tickers and listing currencies (for example a
  fund listed in GBP and EUR); they are one instrument. Names ending in `(Acc)` or `(Dist)` are treated as ETFs.
- **Dates** are the UTC date of the `Time (UTC)` column.
- A cost column the adapter does not know (any other column with `fee`, `tax` or `duty` in its name) that holds a value
  raises WARNING `UNKNOWN_COST_COLUMN` for that row; the amount is not applied.

## Partial exports

A file that covers only part of your history will fail the ledger check `MISSING_PURCHASE_HISTORY` for every sale whose
purchase is in an earlier export. That is intended: import all exports from the first purchase onwards before running
`report`.
