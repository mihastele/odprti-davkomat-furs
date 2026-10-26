# Interactive Brokers adapter (`ibkr`)

Reads the **Activity Statement in CSV format** (IBKR Client Portal: Performance & Reports → Statements → Activity → CSV).
Flex Queries, PDF and HTML statements are not supported.

> **Verification status: not validated against a real statement.** The adapter was written from IBKR's documented
> layout and the fixture `backend/brokers/ibkr/src/test/resources/fixtures/activity-basic.csv` is synthetic. Before
> relying on it, run it on your own statements and compare every trade, dividend and withholding line; send anonymized
> real samples so they become regression fixtures (see [BACKLOG.md](BACKLOG.md), I-02).

## Format handling

Lines look like `Section,RowType,...`. A `Header` row names the columns of the `Data` rows after it, so every field is
looked up **by column name**; column order and extra columns do not matter. A row whose cell count does not match its
header fails with `COLUMN_COUNT` (relevant sections) rather than being guessed at.

## Mapping

| Section | Result |
|---|---|
| Trades, asset category `Stocks` (stocks and ETFs) | `Trade`. Sign of `Quantity` gives BUY/SELL, `T. Price` is the unit price, `Comm/Fee` the fee (absolute value) in the trade currency. Date is the trade date (time dropped). Thousands separators in quantities (`1,000`) are accepted. |
| Trades, `DataDiscriminator` `Order` | mapped. `Trade` (execution) rows are skipped as `EXECUTION_DETAIL` **only if** the file also has `Order` rows (avoids double counting); `ClosedLot`/`Wash Sale` rows are `LOT_DETAIL`. |
| Trades, `Forex` | skipped, `FOREX_TRADE` (INFO) |
| Trades, other categories (options, futures, bonds, CFDs, ...) | skipped with WARNING `UNSUPPORTED_ASSET_CATEGORY` |
| Trade with code `Ca` | mapped, WARNING `CANCELLED_TRADE` |
| Dividends / Payment In Lieu Of Dividends | `DividendPayment`. Rows with the same instrument, date and currency are summed into one entry (extra rows: `DIVIDEND_ROWS_MERGED`). Payments in lieu add WARNING `PAYMENT_IN_LIEU`. Net-negative or net-zero groups fail / are skipped explicitly. |
| Withholding Tax | joined to the dividend with the same instrument, date and currency (`WITHHOLDING_JOINED`). No matching dividend → ERROR `WHT_WITHOUT_DIVIDEND`; net refund → ERROR `WHT_NET_REFUND`. |
| Corporate Actions, forward split (`Split N for M`, N > M, quantity > 0) | `CorporateAction` SPLIT |
| Corporate Actions, anything else (reverse split, merger, spin-off, ISIN change, ...) | `CorporateAction` UNSUPPORTED; `LedgerChecker` warns |
| Transfers, `Stocks` | `SecurityTransfer` IN/OUT; incoming transfers get WARNING `TRANSFER_IN_NO_COST_BASIS` |
| Interest, securities lending, grants | skipped, WARNING `UNSUPPORTED_SECTION` (separate tax forms) |
| `Total` / `SubTotal` rows | skipped, `TOTAL_ROW` |
| Everything else (NAV, cash report, open positions, ...) | skipped, `NOT_TAX_RELEVANT`; `Statement`, `Account Information`, `Financial Instrument Information` are `METADATA` |

Instrument identity: ISIN from `Financial Instrument Information` (`Security ID`) or from the `SYMBOL(ISIN)` prefix of
dividend/corporate-action descriptions. Statements from US entities may carry CUSIPs instead; those instruments stay
without ISIN and the checker warns `MISSING_ISIN`.

Dividend source country: from `- XX Tax` in the withholding description, else the first two letters of the ISIN
(country of issue, which is a proxy, not the paying country). Trade and dividend ids are derived from content (IBKR
Activity Statements carry no transaction id), so re-importing an overlapping statement de-duplicates.

Every line of the file ends as mapped, skipped (reason in a diagnostic) or failed (ERROR); the adapter cannot return a
result that lost a row.
