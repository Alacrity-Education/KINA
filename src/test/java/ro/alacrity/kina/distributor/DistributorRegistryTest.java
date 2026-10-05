package ro.alacrity.kina.distributor;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.Distributor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DistributorRegistryTest {

    private static DistributorClient client(Distributor distributor, boolean configured) {
        DistributorClient client = mock(DistributorClient.class);
        when(client.distributor()).thenReturn(distributor);
        when(client.isConfigured()).thenReturn(configured);
        return client;
    }

    @Test
    void resolvesConfiguredDistributors() {
        DistributorRegistry registry = new DistributorRegistry(
                List.of(client(Distributor.MOUSER, true), client(Distributor.TME, false)));

        assertThat(registry.configured()).containsExactly(Distributor.MOUSER);
        assertThat(registry.resolve(Set.of())).containsExactly(Distributor.MOUSER);
        assertThat(registry.resolve(Set.of(Distributor.TME))).containsExactly(Distributor.TME);
        assertThat(registry.find(Distributor.LCSC)).isEmpty();
        assertThatThrownBy(() -> registry.get(Distributor.LCSC))
                .isInstanceOfSatisfying(DistributorException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("not_configured"));
    }

    @Test
    void rejectsDuplicateClients() {
        assertThatThrownBy(() -> new DistributorRegistry(
                List.of(client(Distributor.TME, true), client(Distributor.TME, true))))
                .isInstanceOf(IllegalStateException.class);
    }
}
