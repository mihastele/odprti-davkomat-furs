# Corporate actions

How the ledger and the tax engine treat them. Every action is an explicit ledger entry; **nothing is inferred**.

| Ledger entry | Effect | Source of entries |
|---|---|---|
| `SPLIT` new/old | lots multiplied by new/old, unit costs divided; acquisition dates unchanged. Reverse splits are new < old. The same split reported by several brokers is applied once. | IBKR (forward splits only), generic CSV (`SPLIT`, any ratio) |
| `CONVERSION` old instrument -> target, new/old | lots of the old instrument move to the target with quantity x new/old and unit cost / (new/old): acquisition dates and total cost carry over. Chains (A -> B -> C) are followed. Reported by several brokers: applied once. | generic CSV only (`CONVERSION` with `new_isin`, `new_symbol`, `new_name`, `split_new`, `split_old`) |
| `UNSUPPORTED` | not applied; a sold instrument is excluded from the report with ERROR `UNSUPPORTED_CORPORATE_ACTION` | IBKR (all but forward splits), Trading 212 (split rows), generic (`CORPORATE_ACTION`) |

## Sheet representation

The Doh-KDVP sheet of a converted instrument is issued for the **final** ISIN. All rows are restated into the units valid
after the year's splits and conversions. Rows for lots that went through a conversion (before or during the year) get
acquisition type `E` ("exchange of capital on status changes of the company"), bought lots `B`. A conversion adds WARNING
`CONVERSION_ASSUMED_TAX_NEUTRAL`: treating an exchange without cash as tax-neutral, with original dates and total cost, is
an unverified assumption (BACKLOG C-01).

## Entering a corporate action by hand

Add a row to a generic CSV and import it together with the broker file:

```
type,date,broker,account,isin,symbol,name,split_new,split_old,new_isin,new_symbol,new_name
CONVERSION,2024-06-01,brokerA,main,US5949181045,OLDX,Old Co,2,1,DE0007164600,NEWX,New Co
SPLIT,2024-03-01,brokerA,main,US0378331005,REVX,Reverse Co,1,10,,,
```

If the broker file already produced an `UNSUPPORTED` entry for the same event, remove that entry from the ledger JSON
(BACKLOG L-13); there is no ignore mechanism yet. See `examples/statement-corporate-actions.csv`.

## Not supported

Mergers with a cash component, spin-offs, stock dividends, rights issues, return of capital, and the cash paid for
fractional shares after a reverse split: these have tax effects that are not modelled, so they stay `UNSUPPORTED`.
