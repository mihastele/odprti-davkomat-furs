# Ignoring ledger entries (overrides)

Set `OPENDAVKI_OVERRIDES_FILE` (see `.env.example`) to a CSV that drops individual ledger entries. Typical uses: an
`UNSUPPORTED` corporate action after you entered the real event by hand (`docs/corporate-actions.md`), or a duplicate that
the importers could not recognise.

```
id,broker,reason
t212-3f2a9c1d44aa1b02,trading212,Stock split entered by hand in corrections.csv
```

- `id` is the ledger entry id (visible in the ledger JSON, and in `audit-<year>.md`); `reason` is **mandatory**; `broker`
  is optional but required when two brokers use the same id (otherwise ERROR `OVERRIDE_AMBIGUOUS`, nothing ignored).
- Every ignored entry is reported as WARNING `ENTRY_IGNORED` with your reason and its statement line, and therefore shows up
  in the audit report: ignoring is a decision that must stay visible. An id that matches nothing is WARNING `OVERRIDE_UNUSED`.
- `import` and `report` both apply the file (it is idempotent); overrides run before the instrument master.
- Only the action `IGNORE` exists. There is no way to edit an entry; correct a wrong entry by ignoring it and adding the right
  one in a generic CSV.
