package si.opendavki.cli;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import si.opendavki.broker.StatementDescriber;
import si.opendavki.broker.StatementSource;
import si.opendavki.broker.XlsxSheets;

/** Describes a CSV, Excel or PDF statement without its contents. Shared by the command line and the local web server. */
final class DescribeRunner {
    private DescribeRunner() {}

    /** @param valueColumns CSV/xlsx: extra columns whose distinct values are listed; PDF: extra words shown unmasked */
    static String describe(StatementSource src, List<String> valueColumns) throws IOException {
        if (PdfDescriber.isPdf(src.content())) {
            return PdfDescriber.describe(src.name(), src.content(), valueColumns);
        }
        if (XlsxSheets.isXlsx(src.content())) {
            StringBuilder out = new StringBuilder();
            try {
                for (Map.Entry<String, List<List<String>>> sheet : XlsxSheets.read(src.content()).entrySet()) {
                    out.append(StatementDescriber.describe(StatementSource.ofText(src.name() + ", sheet \"" + sheet.getKey() + "\"",
                            XlsxSheets.toCsv(sheet.getValue())), valueColumns)).append('\n');
                }
            } catch (IOException e) {
                throw new InputException("Cannot read " + src.name() + " as an Excel workbook: " + e.getMessage());
            }
            return out.toString();
        }
        return StatementDescriber.describe(src, valueColumns);
    }
}
