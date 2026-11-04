package si.opendavki.ledger;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

/**
 * A normalized, broker-independent record. Amounts keep their original currency; EUR conversion is a separate,
 * explicit step so that rates used are visible in the audit trail.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Trade.class, name = "TRADE"),
        @JsonSubTypes.Type(value = DividendPayment.class, name = "DIVIDEND"),
        @JsonSubTypes.Type(value = CorporateAction.class, name = "CORPORATE_ACTION"),
        @JsonSubTypes.Type(value = SecurityTransfer.class, name = "TRANSFER"),
        @JsonSubTypes.Type(value = InterestPayment.class, name = "INTEREST")
})
public sealed interface LedgerEntry permits Trade, DividendPayment, CorporateAction, SecurityTransfer, InterestPayment {
    /** Stable id: the broker's own transaction id when there is one, else derived from the content. */
    String id();
    String broker();
    String account();
    LocalDate date();
    SourceRef source();
    /** The instrument the entry is about; null only for {@link InterestPayment}. */
    Instrument instrument();

    /** Short name of what the entry is about, for messages. */
    default String subject() {
        return instrument() == null ? "interest" : instrument().key();
    }

    /** Same-day processing order: splits first, then incoming positions, buys, sells, outgoing transfers. */
    int dayOrder();

    /** The same entry with every instrument it refers to replaced by {@code f(instrument)} (used by the instrument master). */
    LedgerEntry mapInstruments(UnaryOperator<Instrument> f);
}
