package si.opendavki.broker;

/**
 * A broker statement importer. Implementations live in their own module, are registered through
 * {@code META-INF/services/si.opendavki.broker.BrokerAdapter}, and depend only on core: contributors add a platform
 * without touching the tax engine.
 *
 * <p>Contract: never discard a row silently (see {@link ImportResult.Builder}), never guess missing tax-relevant data,
 * and give every entry a {@link si.opendavki.ledger.SourceRef} to the original line.
 */
public interface BrokerAdapter {
    /** Stable lowercase identifier, e.g. {@code ibkr}. */
    String id();

    String displayName();

    /** Cheap sniff of the file (header/columns) so the CLI can pick an adapter. Must not throw. */
    boolean accepts(StatementSource source);

    ImportResult read(StatementSource source);
}
