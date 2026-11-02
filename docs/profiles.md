# Broker profiles (add a broker without writing Java)

A profile is a JSON file that tells the generic engine how a one-row-per-event CSV is laid out. It exists so that adding or
fixing a broker is a configuration change you can check in minutes, once you have a file of that broker.

```
opendavki describe statement.csv --profile profiles/draft/revolut-trading-draft.json   # does the header fit? what does the file look like?
opendavki profiles profiles/draft/*.json                                              # validate profile files
opendavki import statement.csv --profile my-profile.json -o ledger.json
```

## Safety rules built into the engine

- **Strict header check.** A file whose header lacks any `requiredColumns` entry is rejected with the file's actual header
  (`MISSING_COLUMNS`), so a wrong guess about a layout fails loudly instead of misreading columns.
- **No silent misreads.** Numbers must match the declared format: a thousands separator is only accepted in groups of three
  (so `67.57` in a decimal-comma file is an error, never 6757), a currency written next to a number must equal the row's
  currency, dates must match one of the declared patterns exactly (31 February is an error). Unknown actions are WARNINGs,
  never guessed.
- **Every row is accounted for** like in every adapter: mapped, skipped with a reason, or failed.
- **DRAFT profiles are loud.** A profile written without a real file has `"status": "DRAFT"` and an id ending in `-draft`.
  Every import warns (`DRAFT_IMPORTER`), the report warns (`DRAFT_SOURCE_USED`) and the audit report starts with a banner. To
  make a profile `VERIFIED`: check it against a real file, drop the `-draft` suffix and set the status; a test should then
  cover it.

## Fields (formatVersion 1)

| Field | Meaning |
|---|---|
| `id`, `displayName`, `status`, `note`, `broker`, `account` | identity; `broker`/`account` go into the ledger |
| `delimiter`, `requiredColumns` | one-character delimiter; header names that must all exist |
| `date.column`, `date.patterns` | `DateTimeFormatter` patterns tried in order (e.g. `uuuu-MM-dd'T'HH:mm:ssX`, `dd-MM-uuuu`); only the date is kept |
| `number.decimalSeparator`, `groupSeparator`, `currencyAffix` | how numbers are written; `currencyAffix` allows `USD 12.30` |
| `instrument.isinColumn`, `symbolColumn`, `nameColumn` | columns identifying the security (ISIN optional; use the instrument master to add missing ISINs) |
| `action.column` + `buy`/`sell`/`dividend`/`skip`/`unsupported` | which values of the action column mean what (case-insensitive). `unsupported` rows are skipped with a WARNING |
| `action.signDetermines` | no action column: positive quantity buys, negative sells |
| `trade.quantityColumn`, `priceColumn`, `currencyColumn` | a column reference is a header name, or `after:<name>` for the column right after it (statements with unnamed currency columns) |
| `trade.feeColumn`, `feeCurrencyColumn` / `feeCurrency` | optional fee (absolute value) and its currency, per row or literal |
| `trade.totalColumn`, `totalCheck` | `QTY_PRICE` warns when quantity x price differs from the statement's total by more than 0.02 |
| `dividend.amountIs` | `GROSS` with `grossColumn`, `withholdingColumn`, `currencyColumn` imports dividends; anything else (for example `NET`) means the statement lacks the gross amount and dividend rows are skipped with a WARNING |

## Limits

One file, one row per event. Not supported: several files that must be joined (DEGIRO keeps dividends in a separate Account
statement), several rows that make one event (IBKR-style sections), spreadsheets (`.xlsx`) and PDFs, values that must be parsed
out of free text (XTB comments). Those need a Java adapter (docs/adapters.md).

## Shipped drafts

| Profile | Written from | Known gaps |
|---|---|---|
| `profiles/draft/revolut-trading-draft.json` | recalled layout (`Date, Ticker, Type, Quantity, Price per share, Total Amount, Currency`, currency-prefixed amounts) | no ISIN column (use the instrument master); dividends are skipped because the statement is believed to show them net of withholding; layouts have changed over time |
| `profiles/draft/degiro-transactions-draft.json` | recalled layout (English headers, unnamed currency columns, decimal comma, `dd-MM-yyyy`) | trades only; dividends live in the Account statement; the export language changes the headers |

Both come with synthetic examples in `examples/drafts/` and a test that keeps them working on those examples. Neither has met
a real file. If one does not fit yours, `describe` tells you exactly where it differs.
