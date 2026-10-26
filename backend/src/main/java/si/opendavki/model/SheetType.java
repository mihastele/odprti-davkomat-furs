package si.opendavki.model;

/** Doh-KDVP inventory sheet types ({@code InventoryListType} in Doh_KDVP_9.xsd). */
public enum SheetType {
    /** Securities / investment coupons. */
    PLVP,
    /** Securities, short form (no F10/F11, no resolution). */
    PLVPSHORT,
    /** Securities managed by a broker under a management contract. */
    PLVPGB,
    /** Securities under a management contract, short form. */
    PLVPGBSHORT,
    /** Shares in companies, cooperatives and other entities. */
    PLD,
    /** Securities where share capital was reduced with unchanged quantity. */
    PLVPZOK;

    public boolean isSecurities() {
        return this == PLVP || this == PLVPSHORT || this == PLVPGB || this == PLVPGBSHORT;
    }

    public boolean isShort() {
        return this == PLVPSHORT || this == PLVPGBSHORT;
    }

    public boolean isContract() {
        return this == PLVPGB || this == PLVPGBSHORT;
    }
}
