package si.opendavki.furs;

import org.w3c.dom.Element;
import si.opendavki.model.*;

/** Writes a Doh-Div (dividends) import file with gross amounts and foreign withholding tax per payment. */
public final class DivXmlWriter {

    public String write(DivReport report) {
        XmlBuilder x = new XmlBuilder(FormType.DIV.namespace);
        Element body = x.envelope(report.taxpayer(), "O", false);

        Element root = x.child(body, FormType.DIV.bodyElement);
        x.text(root, "Period", Integer.toString(report.year()));
        x.opt(root, "EmailAddress", report.email());
        x.opt(root, "PhoneNumber", report.phone());
        x.opt(root, "ResidentCountry", report.residentCountry());
        x.text(root, "IsResident", report.resident());
        x.opt(root, "SelfReport", report.selfReport());
        x.opt(root, "Notes", report.notes());

        // Dividend, CorpData, CorpDataDetail and the late-filing blocks are siblings of Doh_Div inside body.
        for (Dividend d : report.dividends()) {
            Element e = x.child(body, "Dividend");
            x.text(e, "Date", d.date());
            x.opt(e, "PayerTaxNumber", d.payerTaxNumber());
            x.opt(e, "PayerIdentificationNumber", d.payerIdentificationNumber());
            x.opt(e, "PayerName", d.payerName());
            x.opt(e, "PayerAddress", d.payerAddress());
            x.text(e, "PayerCountry", d.payerCountry());
            x.text(e, "Type", d.type());
            x.text(e, "Value", XmlBuilder.dec(d.grossValue(), 2));
            x.text(e, "ForeignTax", XmlBuilder.dec(d.foreignTax(), 2));
            x.text(e, "SourceCountry", d.sourceCountry());
            x.opt(e, "ReliefStatement", d.reliefStatement());
        }
        for (CorpData c : report.corpData()) {
            Element e = x.child(body, "CorpData");
            if (c.id() != null) x.text(e, "Id", c.id().toString());
            x.opt(e, "TradeId", c.tradeId());
            x.opt(e, "SoldDate", c.soldDate());
            x.optPlain(e, "CorpAmount", c.corpAmount());
            x.optPlain(e, "CorpShare", c.corpShare());
            x.optPlain(e, "CorpSoldAmount", c.corpSoldAmount());
            x.optPlain(e, "CorpSoldShare", c.corpSoldShare());
            x.opt(e, "NominalTotalValue", c.nominalTotalValue(), 2);
            x.opt(e, "Sum", c.sum(), 2);
        }
        for (CorpDataDetail c : report.corpDataDetails()) {
            Element e = x.child(body, "CorpDataDetail");
            if (c.id() != null) x.text(e, "Id", c.id().toString());
            x.opt(e, "PurchDate", c.purchDate());
            x.opt(e, "PurchType", c.purchType());
            x.optPlain(e, "PurchAmount", c.purchAmount());
            x.optPlain(e, "PurchShare", c.purchShare());
            x.opt(e, "ValueOfPurchased", c.valueOfPurchased(), 2);
            x.opt(e, "ValueAtPurchase", c.valueAtPurchase(), 2);
            x.optPlain(e, "SoldAmount", c.soldAmount());
            x.optPlain(e, "SoldShare", c.soldShare());
            x.opt(e, "SoldValue", c.soldValue(), 2);
            x.opt(e, "SoldSharesValueAtPurchase", c.soldSharesValueAtPurchase(), 2);
            x.opt(e, "GrossSoldValue", c.grossSoldValue(), 2);
        }
        SubseqSubmission s = report.subsequentSubmission();
        if (s != null && s.decision() != null) {
            Element e = x.child(body, "SubseqSubmissDecision");
            x.opt(e, "DecisionId", s.decision().decisionId());
            x.opt(e, "DecisionDate", s.decision().decisionDate());
            x.opt(e, "SubmissionDeadline", s.decision().submissionDeadline());
        }
        if (s != null && s.proposal() != null) {
            Element e = x.child(body, "SubseqSubmissProposal");
            x.opt(e, "ProposalSubmitted", s.proposal().proposalSubmitted());
            x.opt(e, "StartDate", s.proposal().startDate());
            x.opt(e, "ProposalDeadline", s.proposal().proposalDeadline());
            x.opt(e, "DelayReasons", s.proposal().delayReasons());
        }
        return x.serialize();
    }
}
