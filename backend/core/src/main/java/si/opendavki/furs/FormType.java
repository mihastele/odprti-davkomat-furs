package si.opendavki.furs;

/**
 * FURS eDavki forms supported by the XML foundation.
 *
 * <p>Namespace and XSD file name follow the eDavki naming convention ({@code Doh_KDVP_9.xsd}, {@code Doh_Div_3.xsd}).
 * They MUST be confirmed against the official schemas published by FURS before a declaration is filed;
 * the schema version bumps whenever FURS revises the form.
 */
public enum FormType {
    KDVP("Doh_KDVP_9.xsd", "http://edavki.durs.si/Documents/Schemas/Doh_KDVP_9.xsd", "Doh_KDVP"),
    DIV("Doh_Div_3.xsd", "http://edavki.durs.si/Documents/Schemas/Doh_Div_3.xsd", "Doh_Div");

    public static final String EDP_NAMESPACE = "http://edavki.durs.si/Documents/Schemas/EDP-Common-1.xsd";

    public final String xsdFileName;
    public final String namespace;
    public final String bodyElement;

    FormType(String xsdFileName, String namespace, String bodyElement) {
        this.xsdFileName = xsdFileName;
        this.namespace = namespace;
        this.bodyElement = bodyElement;
    }
}
