package si.opendavki.furs;

/**
 * FURS eDavki forms supported by the XML foundation.
 *
 * <p>Namespace and XSD file name follow the eDavki naming convention ({@code Doh_KDVP_9.xsd}, {@code Doh_Div_3.xsd}).
 * They match the newest versions in schemas/ (a test fails when a newer one is added there); the version bumps whenever FURS
 * revises a form and the writers must then be adapted (docs/schemas.md).
 */
public enum FormType {
    KDVP("Doh_KDVP_9.xsd", "http://edavki.durs.si/Documents/Schemas/Doh_KDVP_9.xsd", "Doh_KDVP"),
    DIV("Doh_Div_3.xsd", "http://edavki.durs.si/Documents/Schemas/Doh_Div_3.xsd", "Doh_Div"),
    OBR("Doh_Obr_2.xsd", "http://edavki.durs.si/Documents/Schemas/Doh_Obr_2.xsd", "Doh_Obr");

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
