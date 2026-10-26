package si.opendavki.model;

/** Slovenian taxpayer identity as it appears in the eDavki envelope header. */
public record Taxpayer(String taxNumber, String taxpayerType) {
    public Taxpayer {
        if (taxNumber == null || !taxNumber.matches("\\d{8}")) {
            throw new IllegalArgumentException("taxNumber must be 8 digits (davčna številka), got: " + taxNumber);
        }
        if (taxpayerType == null || taxpayerType.isBlank()) {
            taxpayerType = "FO";
        }
    }
}
