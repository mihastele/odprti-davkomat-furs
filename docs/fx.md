# Exchange rates

Foreign-currency amounts must be converted to EUR for the Slovenian declarations. The source is chosen in `.env`
(see [`.env.example`](../.env.example)); real environment variables override the file.

| `OPENDAVKI_FX_SOURCE` | Data | Status |
|---|---|---|
| `ECB` (**default**) | ECB euro foreign exchange reference rates, full history XML (`eurofxref-hist.xml`), cached on disk | parser tested on a local sample in the ECB `Cube` layout; **download never run from the build environment** |
| `BSI` | Bank of Slovenia's list of ECB reference rates, from the URL in `OPENDAVKI_FX_BSI_URL` (ECB `Cube` XML or `date,currency,rate` CSV, auto-detected) | **unverified**: no official download endpoint or format was confirmed, so there is no default URL |
| `FILE` | your CSV with header `date,currency,rate` (rate = foreign units per 1 EUR), path in `OPENDAVKI_FX_FILE` | works offline; the rates are your responsibility |

Rates are "foreign units per 1 EUR"; `Rate.toEur` divides and keeps 12 decimals, leaving rounding to the declaration.
If the source has no rate for a date, `OPENDAVKI_FX_MISSING_DAY=PREVIOUS` (default) uses the latest earlier rate within
`OPENDAVKI_FX_MAX_LOOKBACK_DAYS` (default 7) and the `Rate` records that it did so (`isFallback`, `rateDate`);
`FAIL` stops instead. Downloads are cached in `OPENDAVKI_FX_CACHE_DIR` (refreshed after 12 h or when a newer date is
needed; a stale cache is used if the download fails; data that cannot be parsed is never cached).

```
java -jar backend/cli/target/opendavki.jar fx USD 2024-03-01 100
```

## Why ECB is the default, and what is not confirmed

Bank of Slovenia publishes the ECB reference rates (its daily list is taken from the ECB website), so `ECB` and `BSI`
carry the same numbers. The default is `ECB` because it is the primary source and has a stable public download.
**The claim "FURS recommends this source and this rate date" could not be verified**: the FURS pages were unreachable
from the build environment and a web search found no FURS instruction on which rate (trade-date rate, previous day,
monthly) the Doh-KDVP/Doh-Div conversion requires. Confirm against the current FURS instructions and ZDoh-2 before
filing, and change the default if FURS says otherwise (BACKLOG X-01, X-04).
