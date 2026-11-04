package si.opendavki.furs;

import org.w3c.dom.Element;
import si.opendavki.model.InterestRow;
import si.opendavki.model.ObrReport;

/**
 * Writes a Doh-Obr (interest) import file, element order following Doh_Obr_2.xsd: the interest rows are children of
 * {@code Doh_Obr} and the body carries {@code edp:bodyContent} (unlike Doh-Div). Optional fields the statements cannot give
 * (payer tax number, address, country, interest type code) are omitted rather than guessed.
 */
public final class ObrXmlWriter {

    public String write(ObrReport report) {
        XmlBuilder x = new XmlBuilder(FormType.OBR.namespace);
        Element body = x.envelope(report.taxpayer(), "O", true);
        Element root = x.child(body, FormType.OBR.bodyElement);
        x.text(root, "Period", Integer.toString(report.year()));
        x.text(root, "DocumentWorkflowID", "O");
        x.text(root, "ResidentOfRepublicOfSlovenia", report.resident());

        for (InterestRow r : report.interest()) {
            Element e = x.child(root, "Interest");
            x.text(e, "Date", r.date());
            x.opt(e, "Name", r.payerName());
            x.opt(e, "Address", r.payerAddress());
            x.opt(e, "Country", r.payerCountry());
            x.opt(e, "Type", r.type());
            x.text(e, "Value", XmlBuilder.dec(r.value(), 2));
            x.text(e, "ForeignTax", XmlBuilder.dec(r.foreignTax(), 2));
            x.opt(e, "Country2", r.sourceCountry());
        }
        return x.serialize();
    }
}
