package si.opendavki.broker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/** Discovers adapters on the classpath. */
public final class BrokerAdapters {
    private BrokerAdapters() {}

    public static List<BrokerAdapter> all() {
        List<BrokerAdapter> l = new ArrayList<>();
        ServiceLoader.load(BrokerAdapter.class).forEach(l::add);
        l.sort(java.util.Comparator.comparing(BrokerAdapter::id));
        return l;
    }

    public static Optional<BrokerAdapter> byId(String id) {
        return all().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /** The single adapter that accepts the file; empty if none, and an exception if the choice is ambiguous. */
    public static Optional<BrokerAdapter> detect(StatementSource s) {
        List<BrokerAdapter> m = all().stream().filter(a -> a.accepts(s)).toList();
        if (m.size() > 1) {
            throw new IllegalStateException(s.name() + " matches several adapters " + m.stream().map(BrokerAdapter::id).toList()
                    + "; choose one explicitly");
        }
        return m.stream().findFirst();
    }
}
