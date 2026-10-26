package si.opendavki.model;

import java.time.LocalDate;

/** Late-filing data for Doh-Div: the authority's decision and/or the taxpayer's proposal. Either part may be null. */
public record SubseqSubmission(Decision decision, Proposal proposal) {
    public record Decision(String decisionId, LocalDate decisionDate, LocalDate submissionDeadline) {}
    public record Proposal(Boolean proposalSubmitted, LocalDate startDate, LocalDate proposalDeadline, String delayReasons) {}
}
