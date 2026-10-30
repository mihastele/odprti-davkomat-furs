# Instrument master

A small CSV you maintain, set with `OPENDAVKI_INSTRUMENTS_FILE` (see `.env.example`). `import` and `report` apply it to the
ledger. It exists because statements are sometimes incomplete about *what* an instrument is.

```
match,isin,asset_class,name
CUSIPX,IE00BFMXXD54,ETF,Vanguard S&P 500 (Acc)
IE00B4ND3602,,ETF,iShares Physical Gold
```

- `match` is an **ISIN**, or a **symbol** for an instrument that has no ISIN (a symbol never overrides an instrument that
  already has one).
- `isin` fills in a missing ISIN, for example for statements that identify securities by CUSIP (Doh-KDVP needs the ISIN).
  If the statement already has a different ISIN the row is ignored with WARNING `INSTRUMENT_MASTER_CONFLICT`.
- `asset_class` is `STOCK`, `ETF`, `FUND` or `OTHER`. ETFs and funds are written with `IsFond = true` on the sheet; the
  adapters only guess (Trading 212: names ending in `(Acc)`/`(Dist)`), so use this to fix wrong guesses.
- `name` replaces the displayed name. Empty cells change nothing; `#` rows are comments.
- Conversion targets are corrected too. Each correction is an INFO `INSTRUMENT_MASTER_APPLIED`; rows that match nothing give
  INFO `INSTRUMENT_MASTER_UNUSED`. Applying the file twice changes nothing more.

The master is your assertion: nothing is guessed or looked up. Wrong ISINs produce wrong declarations.
