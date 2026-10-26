# Writing a broker adapter

An adapter turns one broker's statement into canonical `LedgerEntry` records. It lives in its own Maven module
(`backend/brokers/<id>`), depends only on `opendavki-core`, and never touches the tax engine.

1. Implement `si.opendavki.broker.BrokerAdapter` (`id`, `displayName`, `accepts`, `read`).
2. Register it in `src/main/resources/META-INF/services/si.opendavki.broker.BrokerAdapter`.
3. Add the module to `backend/pom.xml` and as a dependency of `backend/cli/pom.xml`.
4. Add anonymized fixtures under `src/test/resources/fixtures/`: `name.csv` plus `name.expected.json`, and call
   `AdapterFixtures.assertFixture(adapter, path)` (core test-jar). Regenerate expectations deliberately with
   `-DargLine=-Dodv.updateFixtures=true` and review the diff.

## Rules (enforced where possible)

- **No lost rows.** Use `ImportResult.Builder`: every data row must end as `mapped`, `skipped` (with a reason) or
  `failed`; `build(rowsRead)` throws if the numbers do not add up.
- **No guessing.** If a tax-relevant field is missing or ambiguous (ISIN, currency, split ratio), fail the row or emit a
  diagnostic; do not invent a value.
- **Trace everything.** Entries carry a `SourceRef` (`builder.ref(line)`) to the original line and file hash.
- **Stable ids.** Use the broker's transaction id; otherwise `EntryIds.derive(...)`, so overlapping statements de-duplicate.
- **Original currency.** Keep amounts in the statement's currency; EUR conversion is a separate step.
- **Dividends are not trades.** Emit `DividendPayment` with gross and withholding separately.
