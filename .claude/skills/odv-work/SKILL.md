---
name: odv-work
description: Continue OpenDavki development. Use when asked to continue an ODV ticket (ODV-004 onwards), implement the next roadmap item, add a broker adapter, or pay down limitations in docs/BACKLOG.md. Reads AGENTS.md and the backlog, picks work, implements it with tests and docs, and keeps the README limitations in sync.
---

# odv-work

Drive OpenDavki forward one verified increment at a time. `AGENTS.md` is the standard; this skill is the procedure.

## 1. Orient (always)

1. Read `AGENTS.md`, the README (status table, roadmap, Known limitations) and `docs/BACKLOG.md`.
2. `git status`, `git log --oneline | head`, and confirm you are on the assigned branch.
3. Build once to learn the baseline: `cd backend && mvn -q -B install`. If it is not green, fix or report that first.

## 2. Choose the work

- **If the user named a ticket or backlog ID**, do that. Re-read its rows in BACKLOG and the code they touch.
- **Otherwise** take the first open item from this queue, unless blocked (then say why and take the next):
  1. **ODV-004** is done (see docs/tax-engine.md); its open assumptions are the `T-` rows in BACKLOG.
  2. **ODV-005** the audit report is done (`AuditReport`, A-xx rows remain); ledger checks for transfers, prices and precision are done (docs/ledger-checks.md); left: remaining ledger checks (L-03, L-12); semantic
     declaration checks (D-05); IBKR cross-check against IBKR's own realized P/L (I-15).
  3. **ODV-006** is done for splits, reverse splits, conversions and the instrument master (docs/corporate-actions.md); open: cash components (C-03), adapter-generated conversions (C-04, I-05, R-06), ignoring ledger entries (L-13).
  4. **ODV-007** golden files and schema versioning (D-01, D-03, D-04, D-10, D-13).
  5. **ODV-008** CI and licence (L-10). **ODV-009** Vue 3 UI on a local-only backend (L-11).
  6. More brokers in README order, one module each.
- **Items that need the maintainer, not code**: I-02/S-01 (real or paper-account statements, see docs/sample-data.md),
  D-01 (a real eDavki import), X-04 (rate date and rounding). Do not fake these. Prepare everything around them and ask.

Do one coherent increment per run. Do not start a second ticket in the same change.

## 3. Plan briefly

State the intended change, the files/modules, the tests (with how expected values will be derived independently), and
which backlog rows it closes or opens. If a tax rule is involved, name the source you will verify it against; if no
source is reachable, plan the conservative behaviour plus a backlog row.

## 4. Implement

Follow `AGENTS.md`: match the surrounding code, immutable records, `BigDecimal`, fail loudly, provenance on everything,
logic in `core` (or the adapter module), thin CLI. For a new adapter follow `docs/adapters.md` and use the fixture kit.

## 5. Verify

- `cd backend && mvn -q -B install` must be green. For adapters also run the fixture test; review any regenerated
  `expected.json` line by line.
- Validate generated XML against `schemas/` (the writers' tests do; add a case for new output).
- Try the feature through the CLI on a fixture and read the output, not just the exit code.
- If something could not be run (live download, real statement, eDavki import), write that down for step 7.

## 6. Update docs and the backlog (same change)

- Closed a gap: delete its row in `docs/BACKLOG.md` **and** its line in the README "Known limitations" block.
- Opened a gap or made an assumption: add it to both, with an ID and planned ticket.
- Update README status table/roadmap, the relevant `docs/*.md`, and `.env.example` + README config table if settings changed.
- Never remove the README `WIP-NOTICE` block.

## 7. Commit and report

Commit small and clear (imperative subject, body explains why), push to the assigned branch, do not open a PR unless asked.
Final report, plain language: what changed, what was verified and how, what was **not** verified (network, real data,
eDavki), assumptions the maintainers should confirm, and the recommended next item.

## Stop and ask when

A tax interpretation is unclear, a contract used by others would change, real personal data or credentials would be
needed, or the work drifts into other eDavki forms or submission/signing.
