package si.opendavki.ledger;

/** A tradable instrument. The ISIN identifies it across brokers; symbol/name are for humans and fallback matching. */
public record Instrument(String isin, String symbol, String name, AssetClass assetClass) {
    public enum AssetClass { STOCK, ETF, FUND, OTHER }

    public Instrument {
        if ((isin == null || isin.isBlank()) && (symbol == null || symbol.isBlank())) {
            throw new IllegalArgumentException("instrument needs an ISIN or a symbol");
        }
        if (isin != null && isin.isBlank()) isin = null;
        if (assetClass == null) assetClass = AssetClass.OTHER;
    }

    /** Identity used to match the same instrument across brokers. */
    public String key() {
        return isin != null ? isin : "SYMBOL:" + symbol;
    }
}
