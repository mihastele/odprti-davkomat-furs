package si.opendavki.model;

import java.time.LocalDate;

/** Income tax exemption claim in the KDVP header (paragraph/article, residency certificate issuer and date). */
public record Remission(String state, String article, String institution, LocalDate confirmationDate) {}
