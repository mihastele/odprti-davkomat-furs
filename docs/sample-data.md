# Getting sample statements

Every importer is currently written from published layouts and tested on synthetic fixtures, so none is verified
(BACKLOG I-02, S-01). Real-format files are the single most valuable input. Options, roughly in order of effort:

1. **Paper / demo accounts (no personal data at all).** Brokers that offer one can produce statements in the real format
   with fake money. Interactive Brokers: a free paper-trading account has its own Activity Statements (Performance &
   Reports → Statements → Activity → CSV). XTB, Trading 212 and eToro offer demo/practice modes; check whether their
   history export works there. Place a few trades, a dividend-paying stock if possible, a split, and an outgoing and
   incoming transfer if the broker allows it.
2. **Your own statement, redacted by hand.** Remove or replace: account numbers, names, addresses, e-mail, tax numbers,
   order/transaction ids that could identify you, and any free-text notes. Keep column headers, dates, tickers/ISINs,
   quantities and prices unchanged (changing them breaks the checks the fixtures exist for). Quantities may all be
   multiplied by one constant factor if you do not want to disclose holdings.
3. **Documented layouts.** Broker help centres and community threads describe export columns and action names. These
   are enough to write a first adapter and a synthetic fixture, flagged unverified like the IBKR one. The build
   environment can only reach hosts allowed in its network settings, so documentation sites have to be allowed there.
4. **Other open-source projects.** Portfolio trackers and tax tools (for example Ghostfolio exporters, Portfolio
   Performance) keep importers for the same brokers, some with test files. Their layouts are good cross-references;
   copy a file only if its licence allows it and it is clearly synthetic or anonymised, and say where it came from.
5. **Ask the community.** Slovenian investor forums and groups; a donated statement is only acceptable after step 2.

Where files go: `backend/brokers/<id>/src/test/resources/fixtures/`, with the expected result worked out by hand
(AGENTS.md "Testing"). **Never commit an unredacted statement.** If in doubt, keep it out of the repository and tell the
maintainers what it looks like instead.

## Status of broker formats

| Broker | Layout source | Sample available |
|---|---|---|
| Interactive Brokers | IBKR's documented Activity Statement layout | synthetic only |
| Trading 212 | community descriptions of the History CSV (Action, Time, ISIN, Ticker, Name, No. of shares, Price / share, Currency, Exchange rate, Total, Withholding tax, ...); not an official reference | none |
| Revolut, Trade Republic, eToro, XTB, DEGIRO, Lightyear, N26 | not yet researched | none |
