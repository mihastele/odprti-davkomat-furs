package si.opendavki.tax;

import java.util.Map;

/**
 * Switches that change tax results.
 *
 * @param interestTypeCode the Doh-Obr "Šifra vrste obresti" written on every interest row, or null to omit it. The schema does
 *                         not list the codes, so there is no default: confirm the code with FURS (docs/BLOCKERS.md B2).
 * @param includeFees add broker fees to the purchase cost and subtract them from the sale proceeds (unit values in the
 *                    declaration). Believed to match the ZDoh-2 acquisition-cost rules but not verified (BACKLOG T-01).
 */
public record TaxOptions(boolean includeFees, String interestTypeCode) {

    public TaxOptions(boolean includeFees) {
        this(includeFees, null);
    }

    public static TaxOptions defaults() {
        return new TaxOptions(true, null);
    }

    public static TaxOptions from(Map<String, String> env) {
        String v = env.getOrDefault("OPENDAVKI_INCLUDE_FEES", "true").trim().toLowerCase(java.util.Locale.ROOT);
        if (!v.equals("true") && !v.equals("false")) {
            throw new IllegalArgumentException("OPENDAVKI_INCLUDE_FEES must be true or false, got '" + v + "'");
        }
        String code = env.getOrDefault("OPENDAVKI_INTEREST_TYPE_CODE", "").trim();
        return new TaxOptions(Boolean.parseBoolean(v), code.isEmpty() ? null : code);
    }
}
