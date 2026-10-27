# AGENTS.md

Working agreement for anyone (human or AI agent) changing OpenDavki: an open-source, local-first tool that turns broker
statements into validated Slovenian eDavki XML (Doh-KDVP, Doh-Div). The README explains *what* it is; this file explains
*how we work*. The skill [`odv-work`](.claude/skills/odv-work/SKILL.md) turns this into a repeatable workflow for
continuing ODV tickets or paying down the backlog.

## The one rule

**Never produce, or let the code silently produce, a tax number that cannot be explained and traced to a line of an
original broker statement.** Every other rule below serves this one. When in doubt, fail loudly or raise a diagnostic;
never guess.

## Repository map

```
backend/core            ledger, broker adapter SPI, FX rates, tax engine, FURS XML writers + XSD validator (si.opendavki.*)
backend/brokers/<id>    one Maven module per broker adapter; depends only on core
backend/cli             picocli command line, shaded jar, discovers adapters with ServiceLoader
schemas/                official FURS XSDs, byte-for-byte, with SHA256SUMS. Never edit.
examples/               synthetic report inputs
docs/                   adapters.md, generic-csv.md, ibkr.md, fx.md, tax-engine.md, BACKLOG.md
.env.example            every configuration key with its default
```

## Build, test, run

Java 21, Maven. From `backend/`:

```bash
mvn -q -B install                      # build + all tests of all modules (run before every commit)
mvn -q -B install -pl core -DskipTests # refresh core when testing one broker module alone
mvn -q -B test -pl brokers/ibkr        # one module
java -jar cli/target/opendavki.jar --help
```

Regenerate adapter fixture expectations **deliberately** and read the diff:
`mvn -q -B test -pl brokers/<id> -DargLine="-Dodv.updateFixtures=true"`.

The sandbox this project is often developed in cannot reach `edavki.durs.si`, `ecb.europa.eu` or `bsi.si`. Do not claim
network-dependent behaviour works if you could not run it; say so (see "Honesty").

## Code quality

- **Match the surrounding code**: naming, comment density, idiom. Read neighbouring files before writing.
- **Model with immutable records and sealed types** (Java 21). Validate in the compact constructor so an invalid object
  cannot exist; throw `IllegalArgumentException` with a message naming the field and value.
- **Money and quantities are `BigDecimal`. Never `double`/`float`**, not even in tests or intermediate steps. Parse from
  strings, compare with `compareTo` (never `equals`, scale matters), serialise with `toPlainString`. Rounding happens once,
  at the boundary where the declaration needs it (`HALF_UP`, scale stated in the writer), not inside calculations.
- **Dividends and capital gains are separate models and separate forms.** Never net losses against dividends.
- **Keep modules honest**: adapters depend only on `core`; `core` does not know any broker; the CLI is thin (parse
  arguments, call core, print, set exit code). Business logic does not live in `Main`.
- **No cleverness without a comment explaining why.** Comments say *why* or state a contract; they do not narrate code.
  Public types and non-obvious methods get a short Javadoc stating the contract and the failure behaviour.
- **Small, focused methods and files.** If a method needs a section comment to be readable, split it.
- **No dead code, no TODO without a backlog ID** (`// TODO(D-07): ...`). Prefer adding a BACKLOG row to a TODO.
- **Secure by default**: XML parsers with DTDs/external entities disabled; schema loading restricted to local files;
  no shell-outs; no network calls except the explicitly configured ones (`schemas fetch`, FX download). No telemetry.
- **Deterministic output**: stable ordering, no timestamps or random values in generated files and fixtures.

## Correctness rules for tax code

1. **Fail loudly.** Missing history, ambiguous data, unsupported constructs: raise an ERROR diagnostic or exception that
   names the source line. A sale larger than the held quantity is an error, never a silent zero.
2. **Never drop data silently.** If a construct cannot be expressed (e.g. F10/F11 on a short sheet), reject it.
   Adapters must account for every input row (`ImportResult.Builder`): mapped, skipped with a reason, or failed.
3. **Diagnostics**: `UPPER_SNAKE` codes; INFO = understood and intentionally ignored; WARNING = tax-relevant but
   unsupported or suspicious, user must look; ERROR = result is wrong or incomplete until fixed.
4. **Original currency stays original** until the explicit EUR conversion step, and the rate used is recorded.
5. **Provenance on everything**: entries carry `SourceRef`; anything derived keeps a way back to its inputs.
6. **Rules come from sources, not memory.** Slovenian tax rules and FURS formats must be backed by the official XSDs,
   FURS instructions or ZDoh-2. If you cannot verify a rule, implement the conservative behaviour, mark it unverified
   in code and docs, and add a BACKLOG row. Never present an unverified rule as fact.
7. **Schema-valid is necessary, not sufficient.** Validate every generated file against the official XSDs and, where
   possible, against a hand-computed expectation.

## Testing

- Every behaviour change ships with a test; every bug fix ships with a test that fails without the fix.
- Test the failure paths and diagnostics, not only the happy path (negative quantity, missing column, duplicate,
  conflicting duplicate, missing rate, malformed file).
- **Compute expected numbers independently** (by hand or a separate calculation such as a short Python expression),
  never by copying the program's output or typing a guess. A wrong expected value that happens to be "fixed" by pasting
  the output defeats the test.
- Tests must be able to fail: assert specific values and codes, not just "no exception".
- Adapter fixtures: `name.csv` + `name.expected.json` via `AdapterFixtures.assertFixture`. Fixtures are **synthetic or
  properly anonymised**. Never commit real account numbers, names, balances or statements. Review regenerated
  `expected.json` diffs line by line.
- Keep tests offline and deterministic: no live network, no dependence on today's date or the machine's locale/time zone.
- XML tests validate against `schemas/` (relative path from the module, e.g. `../../schemas`).

## Adding or changing a broker adapter

Follow [docs/adapters.md](docs/adapters.md). In short: one module, `ServiceLoader` registration, columns looked up by
name, stable ids, `SourceRef` on every entry, fixtures + test kit, a `docs/<id>.md` describing the mapping and its
**verification status**, and BACKLOG rows for what is unsupported. An adapter that has not seen a real statement says so
in its docs and in the README status table.

## Documentation and the backlog

- `docs/BACKLOG.md` is the single list of known limitations, with IDs (`D-`, `L-`, `I-`, `X-`, ...) and a planned ticket.
  **When you fix a gap, delete its row there and its line in the README "Known limitations" section in the same
  change. When you find a gap, add it to both.** Do not leave stale limitations or hide new ones.
- The README carries a `WIP-NOTICE` block. It is removed only when the maintainers declare the project stable, never by
  a feature change.
- Keep the README status table, roadmap and docs truthful: ✅ only for what is verified, 🟡 for implemented-but-unverified.
- Update `.env.example` whenever a configuration key is added, changed or removed, and document it in the README table.

## Git

- Work on the branch you were assigned; small, focused commits with an imperative subject ("Add X", "Fix Y") and a body
  explaining *why*. Do not rewrite shared history. Do not open a pull request unless asked.
- Run the full build before committing. Do not commit generated output (`target/`, `*.out.xml`), `.env`, caches or
  anything under `private/` (personal financial data).
- Do not weaken or delete a test to get green. Fix the code or, if the test is wrong, fix it with an independent
  expected value and say why in the commit.

## Honesty in reports and docs

State what was run and what was not (live downloads, real statements, eDavki import). Distinguish "tests pass" from
"verified against real data". Say plainly when an assumption was made (for example: the third FX source) so the
maintainers can overturn it. Unverified claims go into the BACKLOG, not into confident prose.

## Ask first when

- A tax interpretation is unclear (which rate date, rounding, treatment of a payment type).
- A change would alter the JSON/ledger/CLI contract used by others (bump `formatVersion`, note it in the backlog).
- You would need real personal financial data or credentials.
- The work crosses into other eDavki forms or submission/signing (out of scope for now).

## Definition of done

- [ ] Behaviour implemented in the right module; no logic leaked into the CLI.
- [ ] Tests added/updated, expected values independently derived, failure paths covered.
- [ ] `mvn -q -B install` green from `backend/`; generated XML validates against `schemas/`.
- [ ] Docs updated (`docs/`, README status/config tables, `.env.example`).
- [ ] BACKLOG and README "Known limitations" updated together (rows removed for fixed gaps, added for new ones).
- [ ] No secrets, personal data or build output committed.
- [ ] The report says what was verified and what was not.
