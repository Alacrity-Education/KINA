package ro.alacrity.kina.distributor;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ro.alacrity.kina.domain.Distributor;

import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Collects every {@link DistributorClient} bean; at most one per {@link Distributor}. */
@Component
public class DistributorRegistry {

    @Autowired private List<DistributorClient> beans;
    private final Map<Distributor, DistributorClient> clients = new EnumMap<>(Distributor.class);

    @PostConstruct
    void index() {
        for (DistributorClient client : beans) {
            DistributorClient previous = clients.putIfAbsent(client.distributor(), client);
            if (previous != null) {
                throw new IllegalStateException("Two DistributorClient beans for " + client.distributor()
                        + ": " + previous.getClass().getName() + " and " + client.getClass().getName());
            }
        }
    }

    public Optional<DistributorClient> find(Distributor distributor) {
        return Optional.ofNullable(clients.get(distributor));
    }

    /** @throws DistributorException NOT_CONFIGURED when no client bean exists for the distributor */
    public DistributorClient get(Distributor distributor) {
        return find(distributor).orElseThrow(() -> DistributorException.notConfigured(distributor));
    }

    /** All registered clients in {@link Distributor} order. */
    public Collection<DistributorClient> all() {
        return List.copyOf(clients.values());
    }

    /** Distributors whose client reports {@link DistributorClient#isConfigured()}. */
    public Set<Distributor> configured() {
        EnumSet<Distributor> result = EnumSet.noneOf(Distributor.class);
        clients.forEach((d, c) -> {
            if (c.isConfigured()) {
                result.add(d);
            }
        });
        return result;
    }

    /** The requested distributors, or all configured ones when {@code requested} is empty. */
    public Set<Distributor> resolve(Set<Distributor> requested) {
        return requested == null || requested.isEmpty() ? configured() : EnumSet.copyOf(requested);
    }
}
