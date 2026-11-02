package si.opendavki.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class PdfDescriberTest {
    private static byte[] pdf(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                float y = 750;
                for (String l : lines) {
                    cs.beginText();
                    cs.newLineAtOffset(50, y);
                    cs.showText(l);
                    cs.endText();
                    y -= 14;
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test void masksNamesAndDigitsButKeepsFinancialVocabulary() throws IOException {
        byte[] pdf = pdf("Max Mustermann", "Musterstrasse 12, 1000 Ljubljana",
                "Kauf Apple Inc. US0378331005 10 Stk. 150,25 EUR", "Dividende 12.50 USD Quellensteuer 1.88", "Order 4711");
        assertTrue(PdfDescriber.isPdf(pdf));
        String md = PdfDescriber.describe("n26.pdf", pdf, List.of());
        assertTrue(md.contains("- pages: 1"), md);
        assertTrue(md.contains("Kauf Aaaaa Aaa. AA9999999999 99 Stk. 999,99 EUR"), md);   // 'Apple' and 'Inc' masked, vocabulary and currency kept
        assertTrue(md.contains("Dividende 99.99 USD Quellensteuer 9.99"), md);
        assertTrue(md.contains("Order 9999"), md);
        for (String secret : List.of("Max", "Mustermann", "Musterstrasse", "Ljubljana", "Apple", "US0378331005", "150", "4711", "1000")) {
            assertFalse(md.contains(secret), secret + " leaked:\n" + md);
        }
    }

    @Test void keepWordsAreShownWhenTheUserAsksForThem() throws IOException {
        String md = PdfDescriber.describe("x.pdf", pdf("Wertpapierabrechnung Sparplan 9 Stk"), List.of("Sparplan"));
        assertTrue(md.contains("Sparplan"), md);
    }

    @Test void notPdfIsDetectedByItsMagicBytes() {
        assertFalse(PdfDescriber.isPdf("a,b\n1,2\n".getBytes()));
        assertFalse(PdfDescriber.isPdf(new byte[0]));
    }

    @Test void maskingKeepsPunctuationAndSpacing() {
        assertEquals("999,99 EUR", PdfDescriber.mask("150,25 EUR", Set.of()));
        assertEquals("Aaaa aaa-Aaaa", PdfDescriber.mask("Jane doe-Smit", Set.of()));
    }
}
