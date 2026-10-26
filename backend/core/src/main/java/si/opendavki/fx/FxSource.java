package si.opendavki.fx;

/** Where exchange rates come from. All rates are "units of foreign currency per 1 EUR" (ECB convention). */
public enum FxSource {
    /** ECB euro foreign exchange reference rates (the default). */
    ECB,
    /** Bank of Slovenia's published list of ECB reference rates; needs {@code OPENDAVKI_FX_BSI_URL}. */
    BSI,
    /** A local CSV (date,currency,rate) for offline use or rates you have verified yourself. */
    FILE
}
