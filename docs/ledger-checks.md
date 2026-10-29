# Ledger checks

`opendavki import` and `opendavki check` run `LedgerChecker` on the merged ledger. It never changes the ledger; every
finding is a diagnostic with the statement line it concerns.

| Code | Severity | Meaning |
|---|---|---|
| `MISSING_PURCHASE_HISTORY` | ERROR | A sale or outgoing transfer would make the position at that broker account negative: a purchase or transfer-in is missing. |
| `MISSING_ISIN` | WARNING | Instrument without ISIN; Doh-KDVP needs it and cross-broker matching falls back to the symbol. |
| `UNSUPPORTED_CORPORATE_ACTION` | WARNING | Anything but a forward split; positions and cost basis may be wrong. |
| `TRANSFER_UNPAIRED` | WARNING | A transfer has no counterpart of the same quantity at another account within 10 days (`TransferPairing`). Import the other broker's statement or supply acquisition date and cost basis. |
| `SUSPICIOUS_PRICE` | WARNING | A trade price is more than 4× above or below the median split-adjusted price of the same instrument and currency (needs at least 3 trades; zero prices are skipped). Usually a split missing from the ledger or a price/quantity unit error. Thresholds are heuristic (BACKLOG L-12). |
| `QUANTITY_PRECISION` | WARNING | A quantity has more than 8 decimals, the most the declaration can carry. |

Merging adds `DUPLICATE_ENTRY` (WARNING, repeat dropped) and `CONFLICTING_DUPLICATE` (ERROR, same id but different content).
The tax engine adds its own diagnostics, see [tax-engine.md](tax-engine.md).
