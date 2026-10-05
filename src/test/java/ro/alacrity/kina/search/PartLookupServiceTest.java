package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.search.PartSearchServiceTest.FakeClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ro.alacrity.kina.search.PartSearchServiceTest.NOW;
import static ro.alacrity.kina.search.PartSearchServiceTest.part;

class PartLookupServiceTest {

    final PartCacheRepository cache = mock(PartCacheRepository.class);
    PartLookupService service;

    PartLookupService service(DistributorClient... clients) {
        service = new PartLookupService(RankingFixtures.properties("kina.search.distributor-timeout", "300ms"),
                new DistributorRegistry(List.of(clients)), new ParametricExtractor(), cache,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return service;
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void freshCachedPartIsServedWithoutCallingTheDistributor() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER);
        when(cache.find(Distributor.MOUSER, "M1", NOW.minus(Duration.ofDays(5))))
                .thenReturn(Optional.of(part(Distributor.MOUSER, "M1")));
        service(mouser);

        PartLookupResponse response = service.lookup(Distributor.MOUSER, " M1 ", false);

        assertThat(response.found()).isTrue();
        assertThat(response.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(response.part().partNumber()).isEqualTo("M1");
        assertThat(response.part().prices()).hasSize(3);
        assertThat(response.part().attributes()).containsKey(ParametricExtractor.CAPACITANCE);
        assertThat(mouser.calls).isEmpty();
    }

    @Test
    void cacheMissFetchesEnrichesAndCaches() {
        FakeClient tme = new FakeClient(Distributor.TME).records(2, i -> part(Distributor.TME, "T" + i));
        when(cache.find(any(), any(), any())).thenReturn(Optional.empty());
        service(tme);

        Optional<PartResponse> part = service.getPart(Distributor.TME, "T1", false);

        assertThat(part).isPresent();
        assertThat(part.get().attributes()).containsEntry(ParametricExtractor.DIELECTRIC, "X7R");
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<Part>> stored = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(cache).upsertAll(stored.capture());
        assertThat(stored.getValue()).singleElement().satisfies(p -> {
            assertThat(p.distributorPartNumber()).isEqualTo("T1");
            assertThat(p.fetchedAt()).isEqualTo(NOW);
            assertThat(p.attributes()).containsKey(ParametricExtractor.CAPACITANCE);
        });
        assertThat(service.lookup(Distributor.TME, "T1", false).cache()).isEqualTo(CacheStatus.MISS);
    }

    @Test
    void bypassCacheSkipsTheCacheRead() {
        FakeClient tme = new FakeClient(Distributor.TME).records(1, i -> part(Distributor.TME, "T0"));
        service(tme);

        PartLookupResponse response = service.lookup(Distributor.TME, "T0", true);

        assertThat(response.cache()).isEqualTo(CacheStatus.BYPASSED);
        verify(cache, never()).find(any(), any(), any());
        verify(cache).upsertAll(anyCollection());
    }

    @Test
    void lcscUsesTheClientAndIsNeverCached() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(1, i -> part(Distributor.LCSC, "C1525"));
        service(lcsc);

        PartLookupResponse response = service.lookup(Distributor.LCSC, "C1525", false);

        assertThat(response.found()).isTrue();
        assertThat(response.cache()).isEqualTo(CacheStatus.NOT_APPLICABLE);
        verify(cache, never()).find(any(), any(), any());
        verify(cache, never()).upsertAll(anyCollection());
    }

    @Test
    void unknownPartIsNotFound() {
        FakeClient tme = new FakeClient(Distributor.TME);
        when(cache.find(any(), any(), any())).thenReturn(Optional.empty());
        service(tme);

        PartLookupResponse response = service.lookup(Distributor.TME, "NOPE", false);

        assertThat(response.found()).isFalse();
        assertThat(response.part()).isNull();
        assertThat(response.error()).isNull();
        assertThat(service.getPart(Distributor.TME, "NOPE", false)).isEmpty();
        verify(cache, never()).upsertAll(anyCollection());
    }

    @Test
    void errorsPropagateAsDistributorExceptions() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER);
        mouser.configured = false;
        DistributorClient slow = new FakeClient(Distributor.TME) {
            @Override
            public Optional<Part> getPart(String distributorPartNumber) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Optional.empty();
            }
        };
        DistributorClient limited = new FakeClient(Distributor.LCSC) {
            @Override
            public Optional<Part> getPart(String distributorPartNumber) {
                throw new DistributorException(Distributor.LCSC, DistributorException.Kind.UNAVAILABLE, "no db");
            }
        };
        when(cache.find(any(), any(), any())).thenReturn(Optional.empty());
        service(mouser, slow, limited);

        assertThatThrownBy(() -> service.lookup(Distributor.MOUSER, "M1", false))
                .isInstanceOfSatisfying(DistributorException.class,
                        e -> assertThat(e.kind()).isEqualTo(DistributorException.Kind.NOT_CONFIGURED));
        long started = System.nanoTime();
        assertThatThrownBy(() -> service.lookup(Distributor.TME, "T1", false))
                .isInstanceOfSatisfying(DistributorException.class,
                        e -> assertThat(e.kind()).isEqualTo(DistributorException.Kind.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThatThrownBy(() -> service.lookup(Distributor.LCSC, "C1", false))
                .isInstanceOfSatisfying(DistributorException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("unavailable"));
        assertThatThrownBy(() -> service.lookup(Distributor.LCSC, " ", false))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cache, never()).find(eq(Distributor.LCSC), any(), any());
    }

    @Test
    void cachedPartKeepsItsOwnTimestamp() {
        Instant old = NOW.minus(Duration.ofDays(2));
        Part cached = part(Distributor.MOUSER, "M9");
        Part aged = cached.toBuilder().fetchedAt(old).build();
        when(cache.find(any(), any(), any())).thenReturn(Optional.of(aged));
        service(new FakeClient(Distributor.MOUSER));

        assertThat(service.lookup(Distributor.MOUSER, "M9", false).cache()).isEqualTo(CacheStatus.HIT);
        verify(cache, never()).upsertAll(anyCollection());
    }
    @Test
    void rateLimitWaitDoesNotCountAgainstTheDistributorTimeout() {
        FakeClient tme = new FakeClient(Distributor.TME) {
            @Override
            public Optional<Part> getPart(String partNumber, Deadline deadline) {
                long wait = Duration.ofMillis(700).toNanos();
                assertThat(deadline.fits(wait)).isTrue();
                deadline.recordWait(deadline.nanoTime(), wait);
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DistributorException(Distributor.TME, DistributorException.Kind.RATE_LIMITED, "interrupted");
                }
                return super.getPart(partNumber);
            }
        }.records(1, i -> part(Distributor.TME, "T0"));
        when(cache.find(any(), any(), any())).thenReturn(Optional.empty());
        service(tme);

        assertThat(service.getPart(Distributor.TME, "T0", false)).isPresent();
    }
}
