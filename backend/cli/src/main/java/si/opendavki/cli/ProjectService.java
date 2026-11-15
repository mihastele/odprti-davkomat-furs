package si.opendavki.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import si.opendavki.json.Json;
import si.opendavki.ledger.Ledger;
import si.opendavki.ledger.ProjectFile;

/** Reads and writes the explicit, user-chosen project file. Nothing is written unless the page asks for it. */
public final class ProjectService {
    private ProjectService() {}

    public static String save(Ledger ledger, Integer year, String taxNumber, boolean includeTaxNumber)
            throws JsonProcessingException {
        String keptTaxNumber = includeTaxNumber && taxNumber != null && !taxNumber.isBlank() ? taxNumber.trim() : null;
        ProjectFile project = new ProjectFile(ProjectFile.CURRENT_FORMAT, ledger,
                new ProjectFile.Report(year, keptTaxNumber));
        return Json.MAPPER.writeValueAsString(project) + "\n";
    }

    public static ProjectFile open(String text) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("The project file is empty");
        return Json.MAPPER.readValue(text, ProjectFile.class);
    }

    public static String suggestedFileName(Integer year) {
        return "opendavki-" + (year == null ? "project" : year) + ".opendavki.json";
    }
}
