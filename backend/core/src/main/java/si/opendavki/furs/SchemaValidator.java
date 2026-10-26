package si.opendavki.furs;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Validates generated XML against the XSDs in a schema directory and reports every failure with its location. */
public final class SchemaValidator {

    public record Issue(String severity, int line, int column, String message) {
        @Override public String toString() { return severity + " at " + line + ":" + column + " - " + message; }
    }

    public record Result(boolean valid, List<Issue> issues) {}

    /** Thrown when the XSD for a form is not present; we never fall back to a guessed schema. */
    public static final class SchemaMissingException extends RuntimeException {
        public SchemaMissingException(String msg) { super(msg); }
    }

    private final Path schemaDir;

    public SchemaValidator(Path schemaDir) {
        this.schemaDir = schemaDir;
    }

    public Path schemaPath(FormType form) {
        return schemaDir.resolve(form.xsdFileName);
    }

    public Result validate(FormType form, String xml) {
        Path xsd = schemaPath(form);
        if (!Files.isRegularFile(xsd)) {
            throw new SchemaMissingException("Schema " + xsd + " not found. Obtain " + form.xsdFileName
                    + " (and the EDP-Common XSD it imports) from the official FURS eDavki page and place it in "
                    + schemaDir + " (see `schemas fetch`).");
        }
        List<Issue> issues = new ArrayList<>();
        try {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            sf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Only local files: imports resolve relative to the XSD, no network access.
            sf.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
            Schema schema = sf.newSchema(xsd.toFile());
            Validator v = schema.newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) { add("WARNING", e); }
                public void error(SAXParseException e) { add("ERROR", e); }
                public void fatalError(SAXParseException e) throws SAXException { add("FATAL", e); throw e; }
                private void add(String sev, SAXParseException e) {
                    issues.add(new Issue(sev, e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
                }
            });
            v.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXException e) {
            if (issues.isEmpty()) issues.add(new Issue("FATAL", -1, -1, e.getMessage()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        boolean valid = issues.stream().noneMatch(i -> !i.severity().equals("WARNING"));
        return new Result(valid, issues);
    }
}
