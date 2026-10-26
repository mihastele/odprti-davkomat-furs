package si.opendavki.furs;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import si.opendavki.model.Taxpayer;

/** Shared DOM helpers for the eDavki envelope. All numbers are formatted from BigDecimal, never double. */
final class XmlBuilder {
    final Document doc;
    private final String ns;

    XmlBuilder(String ns) {
        this.ns = ns;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            this.doc = f.newDocumentBuilder().newDocument();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates the Envelope root with the standard EDP header; returns the {@code body} element to fill.
     * Doh_KDVP_9 requires {@code edp:bodyContent} in the body, Doh_Div_3 does not allow it.
     */
    Element envelope(Taxpayer taxpayer, String workflowId, boolean bodyContent) {
        Element env = doc.createElementNS(ns, "Envelope");
        env.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:edp", FormType.EDP_NAMESPACE);
        doc.appendChild(env);

        Element header = edp(env, "Header");
        Element tp = edp(header, "taxpayer");
        text(tp, FormType.EDP_NAMESPACE, "edp:taxNumber", taxpayer.taxNumber());
        text(tp, FormType.EDP_NAMESPACE, "edp:taxpayerType", taxpayer.taxpayerType());
        Element wf = edp(header, "Workflow");
        text(wf, FormType.EDP_NAMESPACE, "edp:DocumentWorkflowID", workflowId);
        edp(env, "AttachmentList");
        edp(env, "Signatures");

        Element body = child(env, "body");
        if (bodyContent) edp(body, "bodyContent");
        return body;
    }

    private Element edp(Element parent, String local) {
        Element e = doc.createElementNS(FormType.EDP_NAMESPACE, "edp:" + local);
        parent.appendChild(e);
        return e;
    }

    Element child(Element parent, String name) {
        Element e = doc.createElementNS(ns, name);
        parent.appendChild(e);
        return e;
    }

    Element text(Element parent, String name, String value) {
        return text(parent, ns, name, value);
    }

    private Element text(Element parent, String uri, String qname, String value) {
        Element e = doc.createElementNS(uri, qname);
        e.setTextContent(value);
        parent.appendChild(e);
        return e;
    }

    Element text(Element parent, String name, boolean value) {
        return text(parent, name, Boolean.toString(value));
    }

    Element text(Element parent, String name, LocalDate value) {
        return text(parent, name, value.toString());
    }

    /** Optional elements: skipped entirely when the value is absent (the schemas make them minOccurs=0). */
    void opt(Element parent, String name, String value) {
        if (value != null && !value.isBlank()) text(parent, name, value);
    }

    void opt(Element parent, String name, Boolean value) {
        if (value != null) text(parent, name, value.booleanValue());
    }

    void opt(Element parent, String name, LocalDate value) {
        if (value != null) text(parent, name, value);
    }

    void opt(Element parent, String name, BigDecimal value, int scale) {
        if (value != null) text(parent, name, dec(value, scale));
    }

    /** Unrestricted xs:decimal (schema has no digit limits): exact value, no exponent. */
    void optPlain(Element parent, String name, BigDecimal value) {
        if (value != null) text(parent, name, value.stripTrailingZeros().toPlainString());
    }

    /** Fixed-scale decimal string, HALF_UP like the declaration's own rounding. */
    static String dec(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }

    String serialize() {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter w = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(w));
            return w.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
