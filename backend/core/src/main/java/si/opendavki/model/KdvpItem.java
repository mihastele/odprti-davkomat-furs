package si.opendavki.model;

import java.math.BigDecimal;

/**
 * One inventory sheet of a Doh-KDVP declaration. Exactly one of {@code security}, {@code share} or
 * {@code capitalReduction} is set, matching {@code sheetType}.
 *
 * @param name                  sheet name; defaults to the security/share name
 * @param foreignTax            foreign tax paid (EUR) when {@code hasForeignTax}
 * @param foreignTaxCountryId   3-digit FURS country code where the foreign tax was paid
 */
public record KdvpItem(SheetType sheetType, String name,
                       Boolean hasForeignTax, BigDecimal foreignTax, String foreignTaxCountryId, String foreignTaxCountryName,
                       Boolean hasLossTransfer, Boolean foreignTransfer,
                       Boolean taxDecreaseConformance, Boolean taxDecreaseCooperative,
                       Security security, ShareSheet share, CapitalReductionSheet capitalReduction) {

    public KdvpItem {
        if (sheetType == null) throw new IllegalArgumentException("sheetType is required");
        int set = (security != null ? 1 : 0) + (share != null ? 1 : 0) + (capitalReduction != null ? 1 : 0);
        boolean ok = switch (sheetType) {
            case PLVP, PLVPSHORT, PLVPGB, PLVPGBSHORT -> security != null && set == 1;
            case PLD -> share != null && set == 1;
            case PLVPZOK -> capitalReduction != null && set == 1;
        };
        if (!ok) throw new IllegalArgumentException("sheet content does not match sheetType " + sheetType);
        if (name == null || name.isBlank()) {
            name = security != null ? security.name() : share != null ? share.name() : capitalReduction.name();
        }
        if (foreignTax != null && foreignTax.signum() < 0) throw new IllegalArgumentException("foreignTax must be >= 0");
        if (foreignTax != null && foreignTax.signum() > 0 && (foreignTaxCountryId == null || !foreignTaxCountryId.matches("\\d{3}"))) {
            throw new IllegalArgumentException("foreignTaxCountryId (3-digit FURS code) is required when foreignTax is set");
        }
    }

    public static KdvpItem ofSecurity(SheetType type, Security s) {
        return new KdvpItem(type, null, null, null, null, null, null, null, null, null, s, null, null);
    }
}
