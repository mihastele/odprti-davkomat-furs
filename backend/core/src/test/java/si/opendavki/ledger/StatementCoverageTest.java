package si.opendavki.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import si.opendavki.ledger.StatementCoverage.Coverage;

class StatementCoverageTest {
    private static Coverage c(String file, String adapter, String from, String to) {
        return new Coverage(file, adapter, LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test void aHoleBetweenExportsOfOneBrokerIsReported() {
        // 2025-03-31 -> 2025-06-01 is 62 days
        List<Diagnostic> d = StatementCoverage.gaps(List.of(c("b.csv", "trading212", "2025-06-01", "2025-12-31"), c("a.csv", "trading212", "2025-01-01", "2025-03-31")));
        assertEquals(1, d.size());
        assertEquals("STATEMENT_GAP", d.get(0).code());
        assertEquals(Diagnostic.Severity.WARNING, d.get(0).severity());
        assertTrue(d.get(0).message().contains("62 days"), d.get(0).message());
        assertTrue(d.get(0).message().contains("a.csv") && d.get(0).message().contains("b.csv"));
    }

    @Test void contiguousOverlappingAndForeignStatementsAreFine() {
        assertEquals(List.of(), StatementCoverage.gaps(List.of(c("a.csv", "t212", "2025-01-01", "2025-03-31"), c("b.csv", "t212", "2025-04-15", "2025-12-31"))));
        assertEquals(List.of(), StatementCoverage.gaps(List.of(c("a.csv", "t212", "2025-01-01", "2025-06-30"), c("b.csv", "t212", "2025-06-01", "2025-12-31"))));
        assertEquals(List.of(), StatementCoverage.gaps(List.of(c("a.csv", "t212", "2025-01-01", "2025-02-01"), c("b.csv", "ibkr", "2025-12-01", "2025-12-31"))));
        assertEquals(List.of(), StatementCoverage.gaps(List.of(c("a.csv", "t212", "2025-01-01", "2025-02-01"))));
    }

    @Test void exactlyThirtyOneDaysIsNotAGapButThirtyTwoIs() {
        assertEquals(0, StatementCoverage.gaps(List.of(c("a.csv", "x", "2025-01-01", "2025-01-31"), c("b.csv", "x", "2025-03-03", "2025-04-01"))).size()); // 31 days
        assertEquals(1, StatementCoverage.gaps(List.of(c("a.csv", "x", "2025-01-01", "2025-01-31"), c("b.csv", "x", "2025-03-04", "2025-04-01"))).size()); // 32 days
    }

    @Test void aLongStatementCoveringTheHoleHidesTheGap() {
        assertEquals(List.of(), StatementCoverage.gaps(List.of(c("a.csv", "x", "2025-01-01", "2025-12-31"), c("b.csv", "x", "2025-02-01", "2025-02-02"),
                c("c.csv", "x", "2025-10-01", "2025-11-01"))));
    }
}
