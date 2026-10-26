package si.opendavki.furs;

import org.w3c.dom.Element;
import si.opendavki.model.DivReport;
import si.opendavki.model.Dividend;

/** Writes a Doh-Div (dividends) import file with gross amounts and foreign withholding tax per payment. */
public final class DivXmlWriter {

    public String write(DivReport report) {
        XmlBuilder x = new XmlBuilder(FormType.DIV.namespace);
        Element body = x.envelope(report.taxpayer(), "O");
        Element root = x.child(body, FormType.DIV.bodyElement);

        for (Dividend d : report.dividends()) {
            Element e = x.child(root, "Dividend");
            x.text(e, "Date", d.date());
            if (d.payerTaxNumber() != null && !d.payerTaxNumber().isBlank()) {
                x.text(e, "PayerTaxNumber", d.payerTaxNumber());
            }
            x.text(e, "PayerName", d.payerName());
            x.text(e, "PayerAddress", d.payerAddress());
            x.text(e, "PayerCountry", d.payerCountry());
            x.text(e, "Type", d.type());
            x.text(e, "Value", XmlBuilder.dec(d.grossValue(), 2));
            x.text(e, "ForeignTax", XmlBuilder.dec(d.foreignTax(), 2));
            x.text(e, "SourceCountry", d.sourceCountry());
            if (d.reliefStatement() != null && !d.reliefStatement().isBlank()) {
                x.text(e, "ReliefStatement", d.reliefStatement());
            }
        }
        return x.serialize();
    }
}
