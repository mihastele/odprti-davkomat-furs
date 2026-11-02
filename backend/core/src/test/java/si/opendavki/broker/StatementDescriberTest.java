package si.opendavki.broker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatementDescriberTest {
    private static final String CSV = "Action,Time (UTC),ISIN,Name,Account holder,Currency,ID\n"
            + "Market buy,2025-03-03 10:00:00+00:00,US6092071058,Mondelez International,Jane Doe,USD,EOF00000000001\n"
            + "Market sell,2026-01-15 09:00:00+00:00,US2546871060,Walt Disney,Jane Doe,EUR,EOF00000000002\n"
            + "Deposit,2026-01-16 10:00:00+00:00,,,Jane Doe,EUR,\n";

    private static String describe(String csv, String... extra) {
        return StatementDescriber.describe(StatementSource.ofText("f.csv", csv), List.of(extra));
    }

    @Test void shapesFollowTheDocumentedRules() {
        assertEquals("9999-99-99 99:99:99+99:99", StatementDescriber.shape("2025-03-03 10:00:00+00:00"));
        assertEquals("AA9{10}", StatementDescriber.shape("US6092071058"));
        assertEquals("9.9{10}", StatementDescriber.shape("0.2986750000"));
        assertEquals("AAA9{11}", StatementDescriber.shape("EOF00000000001"));
        assertEquals("Aaaa Aaa", StatementDescriber.shape("Jane Doe")); // 'Jane' has four letters, 'Doe' three
    }

    @Test void layoutIsShownButContentsAreNot() {
        String md = describe(CSV);
        assertTrue(md.contains("- data rows: 3"), md);
        assertTrue(md.contains("`Action,Time (UTC),ISIN,Name,Account holder,Currency,ID`"), md);
        assertTrue(md.contains("`9999-99-99 99:99:99+99:99` x3"), md);
        assertTrue(md.contains("`AA9{10}` x2"), md);
        for (String secret : List.of("Jane Doe", "US6092071058", "US2546871060", "EOF00000000001", "Mondelez", "Walt Disney")) {
            assertFalse(md.contains(secret), secret + " must not be in the description:\n" + md);
        }
    }

    @Test void categoricalColumnsShowTheirValues() {
        String md = describe(CSV);
        assertTrue(md.contains("- values: `Deposit` | `Market buy` | `Market sell`"), md);
        assertTrue(md.contains("- values: `EUR` | `USD`"), md);
    }

    @Test void otherColumnsShowValuesOnlyWhenAskedAndNeverIdentifierLookingOnes() {
        assertTrue(describe(CSV, "Account holder").contains("`Jane Doe`")); // the user explicitly asked
        String ids = describe(CSV, "ID");
        assertTrue(ids.contains("values: not shown"), ids);                  // asked, but they look like identifiers
        assertFalse(ids.contains("EOF00000000001"), ids);
    }

    @Test void tooManyDistinctValuesAreNotListedEvenInACategoricalColumn() {
        StringBuilder csv = new StringBuilder("Type,Qty\n");
        for (int i = 0; i < 25; i++) csv.append("kind").append(i).append(",1\n");
        String md = describe(csv.toString());
        assertTrue(md.contains("values: not shown (more than 20"), md);
        assertFalse(md.contains("kind3"), md);
    }

    @Test void otherDelimitersAndBrokenFilesAreHandled() {
        assertTrue(describe("a;b;c\n1;2;3\n").contains("- delimiter: `;`"));
        assertTrue(describe("a\tb\n1\t2\n").contains("- delimiter: `\\t`"));
        assertTrue(describe("a,b\n\"unterminated,1\n").contains("not valid CSV"));
        assertTrue(describe("").contains("empty"));
        assertTrue(describe("a,b\n1,2\n3\n").contains("1 with a different number of cells"));
    }
}
