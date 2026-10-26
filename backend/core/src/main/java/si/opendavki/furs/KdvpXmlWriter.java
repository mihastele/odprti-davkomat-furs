package si.opendavki.furs;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.w3c.dom.Element;
import si.opendavki.model.*;
import si.opendavki.model.SecurityEvent.Kind;

/**
 * Writes a Doh-KDVP (capital gains) import file, element order following Doh_KDVP_9.xsd.
 *
 * <p>Row IDs and the running balance (F8) are derived here so they are always consistent with the rows. Anything the
 * chosen sheet type cannot express (e.g. F10/F11 on a short sheet) is rejected instead of silently dropped, and a sale
 * larger than the held quantity is rejected rather than exported.
 */
public final class KdvpXmlWriter {

    public String write(KdvpReport report) {
        XmlBuilder x = new XmlBuilder(FormType.KDVP.namespace);
        Element body = x.envelope(report.taxpayer(), "O", true);
        Element root = x.child(body, FormType.KDVP.bodyElement);

        writeHeader(x, root, report);
        for (TaxRelief r : report.taxRelief()) writeTaxRelief(x, root, r);
        for (TaxBaseDecrease d : report.taxBaseDecrease()) writeTaxBaseDecrease(x, root, d);
        for (String doc : report.attachments()) x.text(x.child(root, "Attachment"), "Document", doc);

        int itemId = 1;
        for (KdvpItem item : report.items()) writeItem(x, root, item, itemId++);
        return x.serialize();
    }

    private void writeHeader(XmlBuilder x, Element root, KdvpReport r) {
        Map<SheetType, Integer> counts = new EnumMap<>(SheetType.class);
        for (KdvpItem i : r.items()) counts.merge(i.sheetType(), 1, Integer::sum);

        Element k = x.child(root, "KDVP");
        x.text(k, "DocumentWorkflowID", "O");
        x.text(k, "Year", Integer.toString(r.year()));
        x.text(k, "PeriodStart", r.periodStart());
        x.text(k, "PeriodEnd", r.periodEnd());
        x.text(k, "IsResident", r.resident());
        x.opt(k, "CountryOfResidenceID", r.countryOfResidenceId());
        x.opt(k, "CountryOfResidenceName", r.countryOfResidenceName());
        x.opt(k, "TelephoneNumber", r.telephone());
        // The schema requires the five counters; the capital-reduction counter is optional but always written.
        x.text(k, "SecurityCount", count(counts, SheetType.PLVP));
        x.text(k, "SecurityShortCount", count(counts, SheetType.PLVPSHORT));
        x.text(k, "SecurityWithContractCount", count(counts, SheetType.PLVPGB));
        x.text(k, "SecurityWithContractShortCount", count(counts, SheetType.PLVPGBSHORT));
        x.text(k, "ShareCount", count(counts, SheetType.PLD));
        x.text(k, "SecurityCapitalReductionCount", count(counts, SheetType.PLVPZOK));
        if (r.remission() != null) {
            x.opt(k, "RemissionState", r.remission().state());
            x.opt(k, "RemissionArticle", r.remission().article());
            x.opt(k, "ResConfirmationInstitution", r.remission().institution());
            x.opt(k, "ResConfirmationDate", r.remission().confirmationDate());
        }
        x.opt(k, "Email", r.email());
    }

    private static String count(Map<SheetType, Integer> c, SheetType t) {
        return Integer.toString(c.getOrDefault(t, 0));
    }

    private void writeTaxRelief(XmlBuilder x, Element root, TaxRelief t) {
        Element e = x.child(root, "TaxRelief");
        x.opt(e, "OrderNumber", t.orderNumber());
        x.opt(e, "OrderDate", t.orderDate());
        x.opt(e, "AcquirementDate", t.acquirementDate());
        x.opt(e, "ExpropriationDate", t.expropriationDate());
        x.opt(e, "Loss", t.loss(), 2);
        x.opt(e, "Profit", t.profit(), 2);
        x.opt(e, "IncomeTax", t.incomeTax(), 2);
        x.opt(e, "IsPrefilled", t.prefilled());
    }

    private void writeTaxBaseDecrease(XmlBuilder x, Element root, TaxBaseDecrease d) {
        Element e = x.child(root, "TaxBaseDecrease");
        x.opt(e, "OrderNumber", d.orderNumber());
        x.opt(e, "OrderDate", d.orderDate());
        x.opt(e, "TaxOfficeID", d.taxOfficeId());
        x.opt(e, "TaxOfficeName", d.taxOfficeName());
        x.opt(e, "Loss", d.loss(), 2);
    }

    private void writeItem(XmlBuilder x, Element root, KdvpItem i, int itemId) {
        Element item = x.child(root, "KDVPItem");
        x.text(item, "ItemID", Integer.toString(itemId));
        x.text(item, "InventoryListType", i.sheetType().name());
        x.opt(item, "Name", i.name());
        boolean hasForeignTax = i.hasForeignTax() != null ? i.hasForeignTax()
                : i.foreignTax() != null && i.foreignTax().signum() > 0;
        x.text(item, "HasForeignTax", hasForeignTax);
        if (hasForeignTax) {
            x.opt(item, "ForeignTax", i.foreignTax(), 4);
            x.opt(item, "FTCountryID", i.foreignTaxCountryId());
            x.opt(item, "FTCountryName", i.foreignTaxCountryName());
        }
        x.text(item, "HasLossTransfer", Boolean.TRUE.equals(i.hasLossTransfer()));
        x.text(item, "ForeignTransfer", Boolean.TRUE.equals(i.foreignTransfer()));
        x.opt(item, "TaxDecreaseConformance", i.taxDecreaseConformance() != null ? i.taxDecreaseConformance() : Boolean.FALSE);
        x.opt(item, "TaxDecreaseCooperative", i.taxDecreaseCooperative());

        switch (i.sheetType()) {
            case PLVP, PLVPSHORT, PLVPGB, PLVPGBSHORT -> writeSecurities(x, item, i.sheetType(), i.security());
            case PLD -> writeShares(x, item, i.share());
            case PLVPZOK -> writeCapitalReduction(x, item, i.capitalReduction());
        }
    }

    private void writeSecurities(XmlBuilder x, Element item, SheetType type, Security s) {
        boolean shortForm = type.isShort();
        String elementName = switch (type) {
            case PLVP -> "Securities";
            case PLVPSHORT -> "SecuritiesShort";
            case PLVPGB -> "SecuritiesWithContract";
            default -> "SecuritiesWithContractShort";
        };
        if (!type.isContract() && s.stockExchangeName() != null) {
            throw new IllegalArgumentException(s.isin() + ": stockExchangeName is only valid on contract sheets (PLVPGB*)");
        }
        if (shortForm && (s.resolution() != null || s.resolutionDate() != null)) {
            throw new IllegalArgumentException(s.isin() + ": resolution is not available on short sheets");
        }

        Element sec = x.child(item, elementName);
        x.text(sec, "ISIN", s.isin());
        x.opt(sec, "Code", s.code());
        x.opt(sec, "Name", s.name());
        x.text(sec, "IsFond", s.fund());
        if (type.isContract()) x.opt(sec, "StockExchangeName", s.stockExchangeName());
        if (!shortForm) {
            x.opt(sec, "Resolution", s.resolution());
            x.opt(sec, "ResolutionDate", s.resolutionDate());
        }

        int rowId = 0;
        BigDecimal held = BigDecimal.ZERO;
        for (SecurityEvent e : sorted(s)) {
            if (shortForm && (e.reducedUnitPrice() != null || e.art97Rule() != null)) {
                throw new IllegalArgumentException(s.isin() + " on " + e.date()
                        + ": F10/F11 are not available on short sheets [" + e.sourceRef() + "]");
            }
            Element row = x.child(sec, "Row");
            x.text(row, "ID", Integer.toString(rowId++));
            if (e.kind() == Kind.PURCHASE) {
                held = held.add(e.quantity());
                Element p = x.child(row, "Purchase");
                x.text(p, "F1", e.date());
                x.text(p, "F2", e.acquisitionType());
                x.text(p, "F3", XmlBuilder.dec(e.quantity(), 8));
                x.text(p, "F4", XmlBuilder.dec(e.unitPrice(), 8));
                x.opt(p, "F5", e.inheritanceTax(), 4);
                x.opt(p, "F11", e.reducedUnitPrice(), 8);
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
                x.opt(sale, "F10", e.art97Rule());
            }
            x.text(row, "F8", XmlBuilder.dec(held, 8));
        }
    }

    private void writeShares(XmlBuilder x, Element item, ShareSheet s) {
        Element sh = x.child(item, "Shares");
        x.opt(sh, "Name", s.name());
        x.opt(sh, "ForeignCompany", s.foreignCompany());
        x.opt(sh, "DivestmentTaxNumber", s.divestmentTaxNumber());
        x.opt(sh, "SubsequentPayments", s.subsequentPayments());
        for (ShareSheet.SubsequentPayment p : s.subsequentPaymentRows()) {
            Element e = x.child(sh, "SubsequentPaymentRow");
            x.opt(e, "PaymentTaxNumber", p.taxNumber());
            x.opt(e, "PaymentDate", p.date());
            x.opt(e, "PaymentAmount", p.amount(), 4);
        }
        int rowId = 0;
        for (ShareSheet.Row r : s.rows()) {
            Element row = x.child(sh, "Row");
            x.text(row, "ID", Integer.toString(rowId++));
            if (r.kind() == Kind.PURCHASE) {
                Element p = x.child(row, "Purchase");
                x.text(p, "F1", r.date());
                x.text(p, "F2", r.acquisitionType());
                x.text(p, "F3", XmlBuilder.dec(r.value(), 8));
                x.opt(p, "F4", r.inheritanceTax(), 4);
            } else {
                Element sale = x.child(row, "Sale");
                x.text(sale, "F5", r.date());
                x.text(sale, "F6", XmlBuilder.dec(r.percent(), 4));
                x.text(sale, "F8", XmlBuilder.dec(r.saleValue(), 8));
                x.opt(sale, "F9", r.art97Rule());
            }
            x.opt(row, "F7", r.balance(), 8);
        }
    }

    private void writeCapitalReduction(XmlBuilder x, Element item, CapitalReductionSheet s) {
        Element sec = x.child(item, "SecuritiesCapitalReduction");
        x.opt(sec, "ISIN", s.isin());
        x.opt(sec, "Code", s.code());
        x.opt(sec, "Name", s.name());
        x.opt(sec, "Resolution", s.resolution());
        x.opt(sec, "ResolutionDate", s.resolutionDate());
        int rowId = 0;
        for (CapitalReductionSheet.Row r : s.rows()) {
            Element row = x.child(sec, "Row");
            x.text(row, "ID", Integer.toString(rowId++));
            if (r.kind() == Kind.PURCHASE) {
                Element p = x.child(row, "Purchase");
                x.text(p, "F1", r.date());
                x.text(p, "F2", r.acquisitionType());
                x.text(p, "F3", XmlBuilder.dec(r.quantity(), 8));
                x.text(p, "F4", XmlBuilder.dec(r.unitPrice(), 8));
            } else {
                Element sale = x.child(row, "Sale");
                x.text(sale, "F5", r.date());
                x.text(sale, "F6", XmlBuilder.dec(r.percent(), 4));
                x.text(sale, "F7", XmlBuilder.dec(r.payout(), 8));
            }
        }
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
