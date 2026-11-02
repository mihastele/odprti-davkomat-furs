# What is blocked, and what you need to do

Everything the code can do without outside information is done; what is left in [BACKLOG.md](BACKLOG.md) is either a heuristic to
revisit or listed here. The items below need a **decision, a document, a file of yours, or network access** that I cannot get
from the build environment. Nothing here can be faked: the tool would then print confident numbers that nobody has checked.

Order = importance for filing season. **Do not file a declaration produced by this tool until B1 and B2 are done for your own
data.**

## B1. Compare one real declaration with eDavki (D-01, T-02, T-03, C-02, T-07)

Why: the XML validates against the official schemas, but no generated file has ever been imported into eDavki, and the sheet
layout (open lots carried into the year as purchase rows, restated into post-split units, converted rows typed `E`) is my
assumption of what FURS expects.

You do:
1. Export **all** your Trading 212 (and any IBKR) statements from your first purchase to the end of the last complete year.
2. `java -jar backend/cli/target/opendavki.jar import <all files> -o ledger.json` and fix every ERROR it prints
   (`MISSING_PURCHASE_HISTORY` means an export is missing; `STATEMENT_GAP` points at the hole).
3. `... report ledger.json --year <year> --tax-number <yours> --out-dir out`.
4. In eDavki, open the Doh-KDVP form and use its XML import on `out/kdvp-<year>.xml`; do the same for `out/div-<year>.xml` on Doh-Div.
5. Compare eDavki's computed gain/loss per security with `out/audit-<year>.md` ("Summary of capital gains"), and look at the
   imported rows.
6. Tell me: accepted or rejected (with the exact message), and any per-security difference. Totals are enough; no personal data.

Then I: fix whatever differs, turn the example into a golden test, and update the T-/C- assumptions.

## B2. Tax-rule questions only FURS or a tax adviser can answer (X-04, T-01, C-01, T-03, T-08, I-14, R-02, D-07, T-06, I-07)

Why: these are interpretations of ZDoh-2 / FURS practice. The code makes a conservative choice and says so; the choices are
unconfirmed. You told me FURS recommends the ECB reference rates published by Banka Slovenije; the rest is open.

You do: ask the FURS information service or a Slovenian tax adviser (send them the list, ask for the answer in writing):

1. Which date's ECB rate converts a purchase, a sale and a dividend (the transaction date? the day before?) and where is rounding
   applied (each amount, or only totals)? / *Kateri datum referenčnega tečaja ECB se uporabi pri preračunu nakupa, prodaje in dividende in kdaj se zaokrožuje?*
2. Are broker commissions and currency-conversion fees added to the acquisition value and deducted from the sale value in the
   unit values of Doh-KDVP? / *Ali se provizije in stroški menjave valute všteje v nabavno vrednost oziroma odštejejo od vrednosti ob odsvojitvi?*
3. After an exchange of shares without cash (ISIN change, merger paid in shares): do acquisition date and value carry over, and
   how must the old and the new security appear in Doh-KDVP (acquisition type "E", one sheet or two)? / *Kako se prikaže zamenjava vrednostnih papirjev brez denarnega plačila?*
4. For a security partly sold in earlier years, should the sheet list only the remaining quantity of the open lot (what the tool
   does) or the full history? / *Ali se na popisnem listu prikaže samo preostala količina ali celotna zgodovina?*
5. Is an ETF or ETC (for example physical gold) a "sklad" (`IsFond` true) for Doh-KDVP?
6. Is a "payment in lieu of dividend" / "manufactured dividend" (shares lent out) a dividend for Doh-Div?
7. For a trade shortly before or after midnight UTC, which calendar date counts, and in what order are same-day purchases and sales
   matched?
8. For dividends from foreign companies, are the payer's tax number and address mandatory in Doh-Div when the broker does not give them?

I then: change the defaults and the `T-`/`C-`/`X-`/`R-` rows accordingly (`OPENDAVKI_TIMEZONE`, `OPENDAVKI_INCLUDE_FEES` already
exist as switches).

## B3. Exchange rates from the real sources (X-02, X-03)

Why: the sandbox cannot reach ecb.europa.eu or bsi.si, so the ECB download was never run live, and I found no official Bank of
Slovenia download address.

You do (either):
- Run `java -jar backend/cli/target/opendavki.jar fx USD 2025-03-03 100` on your own computer. If it prints a rate, X-03 is
  confirmed; if not, send me the error. Or
- In the cloud environment settings (environment menu in the session title bar, Edit, Network access) allow `ecb.europa.eu`,
  `bsi.si` (and `edavki.durs.si`, `davkomat.si`, `github.com`, `raw.githubusercontent.com` if you want me to read them), then say so.
- If you prefer the Bank of Slovenia as the source: find the page/file that lists the ECB reference rates there and send me the URL
  of the downloadable file (XML or CSV). Then `OPENDAVKI_FX_SOURCE=BSI` works.

## B4. A real file of every broker you use (I-02, P-01, P-03, P-04, P-05, R-01, S-01)

Why: layouts and, more importantly, what each column *means* (net or gross, signs, which date) cannot be known without a file. I
refuse to guess tax inputs, so Revolut and DEGIRO exist only as DRAFT profiles and the others do not exist.

You do, per broker you actually use: export a statement, then run **`describe`** on it (it prints column names and value shapes,
and the PDF/xlsx variants mask digits and words; nothing personal) and paste the output to me. Read it first.

```
java -jar backend/cli/target/opendavki.jar describe <file.csv|file.pdf|file.xlsx>
```

| Broker | Where to export (as far as I could find; confirm in the app) | What to send |
|---|---|---|
| **Revolut** | Invest, then More, Documents, Stocks, Account statement, CSV (Excel/PDF also offered) | `describe` of the CSV; plus the annual tax/consolidated statement if you have one (dividends are shown net) |
| **N26** | N26 Invest trades are handled by Upvest; the Invest documents seem to be PDFs ("securities settlement"), the Account Activity CSV is bank transactions only | `describe` of one PDF trade confirmation and any annual tax report; ask N26 support whether a CSV/annual tax report for Invest exists |
| **Trading 212** | History, Export CSV (one year per file) | done once; send the other years' exports to test the full pipeline |
| **XTB** | xStation, History, Cash operations, Export (CSV/XLSX) | `describe` of the export |
| **eToro** | Account Statement, export to XLS (sheets Closed Positions, Account Activity, Dividends) | `describe` of the xlsx |
| **DEGIRO** | Transactions CSV **and** Account statement CSV | `describe` of both |
| **Lightyear** | Statements: Account, Income, Capital gains (PDF) | `describe` of the PDFs |
| **Trade Republic** | PDF statements, or the `pytr` export tool | `describe` of the file you have |
| **Interactive Brokers** | Performance and Reports, Statements, Activity, CSV; a free **paper-trading** account produces real-format statements with fake money | the statement itself if from a paper account, else `describe` |

I then: write or correct the profile/adapter, add a synthetic fixture with the same structure, and check the figures by hand.

## B5. Choose a licence (L-10)

Why: a licence is the owner's decision. There is no LICENSE file, so legally nobody may reuse the code yet.

You do: pick one and tell me. My suggestion: **Apache-2.0** (permissive, patent grant, easy for contributors and companies) or
**AGPL-3.0** (anyone offering a modified hosted version must publish it; fits a privacy-first tool). I add the file and headers.

## B6. Other eDavki forms (D-09, T-07, I-04, I-10, L-06)

Why: only Doh-KDVP and Doh-Div schemas are in the repository. Interest on cash or lent shares (Doh-Obr), derivatives/CFDs and
crypto have other forms; I do not have their XSDs.

You do: tell me which of these you have income from. Download the matching XSD(s) from the eDavki XML schema page
(`https://edavki.durs.si/EdavkiPortal/OpenPortal/Pages/Technicals/FormsXml.aspx`) into `schemas/`, add their SHA-256 to
`schemas/SHA256SUMS` (`sha256sum`), and commit.

I then: add the model, writer, adapters' mapping and tests for those forms.

## B7. Special cases (D-04, D-10)

If you need a **correction** of an earlier declaration, a **late filing**, a **non-resident or part-year** declaration or an
exemption claim, tell me the case. The models exist but have never been tried against real requirements.

## B8. Turn on the CI check (L-10)

`.github/workflows/ci.yml` builds and tests every push, but GitHub Actions has not run it yet. In the repository settings make
sure Actions are enabled and look at the first run; send me the log if it is red.

---

## "Ready for the filing season" checklist

- [ ] B1 done for your own data and the numbers agree with eDavki.
- [ ] B2 answered; defaults updated.
- [ ] B3 the rate source works from your machine.
- [ ] B4 every broker you use has a verified importer (no `DRAFT_IMPORTER`/`DRAFT_SOURCE_USED` warning in your report).
- [ ] `report` finishes with `0 error(s)` and the audit report banner says *complete*.
- [ ] You tried `serve` on your own machine with your own statements and nothing in it confused or surprised you (tell me what did).
- [ ] B5 licence chosen (only matters for publishing).
