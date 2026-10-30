# Generic CSV format

The format to convert to when no dedicated broker adapter exists. One header row, then one row per transaction.
Rows starting with `#` are comments (reported as skipped, never silently dropped).
Dates are `yyyy-MM-dd`; numbers are plain decimals with `.` (no thousands separators, no exponent).

| column | used by | notes |
|---|---|---|
| `type` | all | `BUY`, `SELL`, `DIVIDEND`, `SPLIT`, `CONVERSION`, `CORPORATE_ACTION`, `TRANSFER_IN`, `TRANSFER_OUT` |
| `date` | all | trade/pay/ex/transfer date |
| `broker`, `account` | all | default `generic` / `default`; positions are tracked per broker account |
| `id` | all | broker transaction id; if empty a stable id is derived from the row content |
| `isin`, `symbol`, `name`, `asset_class` | all | ISIN or symbol required (ISIN strongly preferred); `STOCK/ETF/FUND/OTHER` |
| `quantity`, `price`, `currency` | BUY/SELL | price per unit; `TRANSFER_*` use `quantity`, and `price`+`currency` as cost basis per unit |
| `fee`, `fee_currency` | BUY/SELL | total commission; currency defaults to `currency` |
| `gross`, `withholding`, `source_country` | DIVIDEND | gross and foreign tax withheld, in `currency`; ISO country |
| `split_new`, `split_old` | SPLIT, CONVERSION | holders get `new` shares per `old` held (4-for-1: 4 / 1; reverse 1-for-10: 1 / 10) |
| `new_isin`, `new_symbol`, `new_name` | CONVERSION | the instrument the old one is exchanged into (`new_isin` required), see [corporate-actions.md](corporate-actions.md) |
| `acquisition_date`, `counterparty` | TRANSFER_IN/OUT | original acquisition date lets cross-broker FIFO keep holding periods |
| `description` | CORPORATE_ACTION | anything the engine cannot apply automatically; raises a warning |

Required header columns: `type,date,isin,symbol,quantity,price,currency`. Unknown extra columns are ignored.
