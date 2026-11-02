package si.opendavki.cli;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Describes the text layout of a PDF statement without its contents, like {@code StatementDescriber} does for CSV:
 * every digit becomes 9 and every word is masked (capitals A, lowercase a) unless it is in the built-in vocabulary of
 * financial terms, is a currency code, or was named with {@code --keep}. Names, addresses, company names and ids therefore
 * never appear. The output is meant for writing a parser; read it before sharing it.
 */
final class PdfDescriber {
    /** Words that describe what a line is (buy, dividend, tax, ...) in the languages brokers of interest use. Public vocabulary only. */
    private static final Set<String> VOCABULARY = Set.copyOf(new java.util.HashSet<>(List.of(
            "buy", "sell", "purchase", "sale", "dividend", "dividends", "tax", "taxes", "withholding", "fee", "fees", "commission", "total", "amount",
            "quantity", "shares", "share", "price", "value", "date", "settlement", "trade", "order", "isin", "security", "securities", "currency",
            "exchange", "rate", "net", "gross", "interest", "deposit", "withdrawal", "transfer", "split", "statement", "account", "page", "balance",
            "kauf", "verkauf", "dividende", "steuer", "quellensteuer", "gebühr", "gebuehr", "provision", "stück", "stk", "stk.", "kurs", "betrag", "wert",
            "datum", "ausführung", "wertpapier", "wertpapierabrechnung", "abrechnung", "zinsen", "einzahlung", "auszahlung", "summe", "menge", "nakup", "nakup",
            "prodaja", "dividenda", "davek", "provizija", "količina", "cena", "znesek", "datum", "skupaj", "obresti", "achat", "vente", "dividende", "frais",
            "impôt", "quantité", "prix", "montant", "acquisto", "vendita", "dividendo", "imposta", "commissione", "quantità", "prezzo", "importo", "compra",
            "venta", "impuesto", "comisión", "cantidad", "precio", "importe")));
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Set<String> CURRENCIES = Set.of("EUR", "USD", "GBP", "CHF", "GBX", "PLN", "CZK", "HUF", "SEK", "NOK", "DKK", "CAD", "AUD", "JPY");
    private static final int MAX_PAGES = 3, MAX_LINES_PER_PAGE = 120;

    private PdfDescriber() {}

    static boolean isPdf(byte[] content) {
        return content.length > 4 && content[0] == '%' && content[1] == 'P' && content[2] == 'D' && content[3] == 'F';
    }

    static String describe(String fileName, byte[] content, Collection<String> keepWords) throws IOException {
        Set<String> keep = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        keep.addAll(keepWords);
        StringBuilder md = new StringBuilder("# Layout of ").append(fileName).append(" (PDF)\n\n")
                .append("Digits are shown as 9; words are masked (capitals A, lowercase a) unless they are financial vocabulary, currency codes or ")
                .append("words you passed with `--keep`. **Read this before sharing it.**\n\n");
        try (PDDocument doc = Loader.loadPDF(content)) {
            int pages = doc.getNumberOfPages();
            md.append("- pages: ").append(pages).append("\n- shown: the first ").append(Math.min(pages, MAX_PAGES)).append(" page(s)\n\n");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int p = 1; p <= Math.min(pages, MAX_PAGES); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                List<String> lines = stripper.getText(doc).lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
                md.append("## Page ").append(p).append(" (").append(lines.size()).append(" lines)\n\n```\n");
                lines.stream().limit(MAX_LINES_PER_PAGE).forEach(l -> md.append(mask(l, keep)).append('\n'));
                if (lines.size() > MAX_LINES_PER_PAGE) md.append("... ").append(lines.size() - MAX_LINES_PER_PAGE).append(" more lines\n");
                md.append("```\n\n");
            }
        }
        return md.toString();
    }

    static String mask(String line, Set<String> keep) {
        StringBuilder out = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (char ch : (line + " ").toCharArray()) {
            if (Character.isLetter(ch)) {
                word.append(ch);
                continue;
            }
            if (word.length() > 0) {
                out.append(maskWord(word.toString(), keep));
                word.setLength(0);
            }
            out.append(Character.isDigit(ch) ? '9' : ch);
        }
        return out.toString().stripTrailing();
    }

    private static String maskWord(String w, Set<String> keep) {
        if (keep.contains(w) || VOCABULARY.contains(w.toLowerCase(Locale.ROOT)) || (CURRENCY.matcher(w).matches() && CURRENCIES.contains(w))) return w;
        StringBuilder b = new StringBuilder();
        for (char c : w.toCharArray()) b.append(Character.isUpperCase(c) ? 'A' : 'a');
        return b.toString();
    }
}
