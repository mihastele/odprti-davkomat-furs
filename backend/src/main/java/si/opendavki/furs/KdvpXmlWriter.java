package si.opendavki.furs;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.w3c.dom.Element;
import si.opendavki.model.KdvpReport;
import si.opendavki.model.Security;
import si.opendavki.model.SecurityEvent;

/**
 * Writes a Doh-KDVP (capital gains on securities) import file.
 *
 * <p>The remaining-stock column (F8) is derived here as a running balance so it is always consistent with the rows;
 * a sale larger than the held quantity is rejected rather than silently exported.
 */
public final class KdvpXmlWriter {

    public String write(KdvpReport report) {
        XmlBuilder x = new XmlBuilder(FormType.KDVP.namespace);
        Element body = x.envelope(report.taxpayer(), "O", true);
        Element root = x.child(body, FormType.KDVP.bodyElement);

        Element kdvp = x.child(root, "KDVP");
        x.text(kdvp, "DocumentWorkflowID", "O");
        x.text(kdvp, "Year", Integer.toString(report.year()));
        x.text(kdvp, "PeriodStart", LocalDate.of(report.year(), 1, 1));
        x.text(kdvp, "PeriodEnd", LocalDate.of(report.year(), 12, 31));
        x.text(kdvp, "IsResident", report.resident());
        // The schema requires all five counters; only plain PLVP sheets (Securities) are produced so far.
        x.text(kdvp, "SecurityCount", Integer.toString(report.securities().size()));
        x.text(kdvp, "SecurityShortCount", "0");
        x.text(kdvp, "SecurityWithContractCount", "0");
        x.text(kdvp, "SecurityWithContractShortCount", "0");
        x.text(kdvp, "ShareCount", "0");

        int itemId = 1;
        for (Security s : report.securities()) {
            Element item = x.child(root, "KDVPItem");
            x.text(item, "ItemID", Integer.toString(itemId++));
            x.text(item, "InventoryListType", "PLVP");
            x.text(item, "Name", s.name());
            x.text(item, "HasForeignTax", report.hasForeignTax());
            x.text(item, "HasLossTransfer", false);
            x.text(item, "ForeignTransfer", false);
            x.text(item, "TaxDecreaseConformance", false);

            Element sec = x.child(item, "Securities");
            x.text(sec, "ISIN", s.isin());
            x.text(sec, "Code", s.code());
            x.text(sec, "Name", s.name());
            x.text(sec, "IsFond", s.fund());

            int rowId = 0;
            BigDecimal held = BigDecimal.ZERO;
            for (SecurityEvent e : sorted(s)) {
                Element row = x.child(sec, "Row");
                x.text(row, "ID", Integer.toString(rowId++));
                if (e.kind() == SecurityEvent.Kind.PURCHASE) {
                    held = held.add(e.quantity());
                    Element p = x.child(row, "Purchase");
                    x.text(p, "F1", e.date());
                    x.text(p, "F2", e.acquisitionType());
                    x.text(p, "F3", XmlBuilder.dec(e.quantity(), 8));
                    x.text(p, "F4", XmlBuilder.dec(e.unitPrice(), 8));
                } else {
                    held = held.subtract(e.quantity());
                    if (held.signum() < 0) {
                        throw new IllegalArgumentException("Sale of " + s.isin() + " on " + e.date() + " exceeds held quantity ("
                                + "missing purchase records?) [" + e.sourceRef() + "]");
                    }
                    Element sale = x.child(row, "Sale");
                    x.text(sale, "F6", e.date());
                    x.text(sale, "F7", XmlBuilder.dec(e.quantity(), 8));
                    x.text(sale, "F9", XmlBuilder.dec(e.unitPrice(), 8));
                }
                x.text(row, "F8", XmlBuilder.dec(held, 8));
            }
        }
        return x.serialize();
    }

    /** Chronological; on the same day purchases precede sales so same-day round trips are never negative. */
    private static List<SecurityEvent> sorted(Security s) {
        List<SecurityEvent> l = new ArrayList<>(s.events());
        l.sort((a, b) -> {
            int c = a.date().compareTo(b.date());
            return c != 0 ? c : a.kind().compareTo(b.kind());
        });
        return l;
    }
}
