package ro.alacrity.kina.search;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.PartLookupResult;
import ro.alacrity.kina.domain.Availability;
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
        service = TestWiring.wire(new PartLookupService(),
                "properties", RankingFixtures.properties("kina.search.distributor-timeout", "300ms"),
                "registry", TestWiring.registry(List.of(clients)), "extractor", new ParametricExtractor(),
                "partCache", cache, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
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
        when(cache.find(Distributor.MOUSER, "M1"))
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
        when(cache.find(any(), any())).thenReturn(Optional.empty());
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
        verify(cache, never()).find(any(), any());
        verify(cache).upsertAll(anyCollection());
    }

    @Test
    void lcscUsesTheClientAndIsNeverCached() {
        FakeClient lcsc = new FakeClient(Distributor.LCSC).records(1, i -> part(Distributor.LCSC, "C1525"));
        service(lcsc);

        PartLookupResponse response = service.lookup(Distributor.LCSC, "C1525", false);

        assertThat(response.found()).isTrue();
        assertThat(response.cache()).isEqualTo(CacheStatus.NOT_APPLICABLE);
        verify(cache, never()).find(any(), any());
        verify(cache, never()).upsertAll(anyCollection());
    }

    @Test
    void unknownPartIsNotFound() {
        FakeClient tme = new FakeClient(Distributor.TME);
        when(cache.find(any(), any())).thenReturn(Optional.empty());
        service(tme);

        PartLookupResponse response = service.lookup(Distributor.TME, "NOPE", false);

        assertThat(response.found()).isFalse();
        assertThat(response.part()).isNull();
        assertThat(response.error()).isNull();
        assertThat(response.reason()).isEqualTo(PartLookupResponse.NOT_FOUND);
        assertThat(response.identity()).isNull();
        assertThat(service.getPart(Distributor.TME, "NOPE", false)).isEmpty();
        verify(cache, never()).upsertAll(anyCollection());
    }

    @Test
    void listedWithoutStockIsOutOfStockWithItsIdentityAndNeverCached() {
        FakeClient mouser = new FakeClient(Distributor.MOUSER) {
            @Override
            public PartLookupResult lookup(String partNumber, Deadline deadline) {
                return PartLookupResult.outOfStock(new PartLookupResult.Identity("667-ERA-6AEB5361V", "Panasonic",
                        "ERA-6AEB5361V", "Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm"));
            }
        };
        when(cache.find(any(), any())).thenReturn(Optional.empty());
        service(mouser);

        PartLookupResponse response = service.lookup(Distributor.MOUSER, "ERA6AEB5361V", false);

        assertThat(response.found()).isFalse();
        assertThat(response.reason()).isEqualTo(PartLookupResponse.OUT_OF_STOCK);
        assertThat(response.error()).isNull();
        assertThat(response.part()).isNull();
        assertThat(response.identity()).isEqualTo(new PartLookupResponse.Identity("667-ERA-6AEB5361V", "Panasonic",
                "ERA-6AEB5361V", "Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm"));
        assertThat(service.getPart(Distributor.MOUSER, "ERA6AEB5361V", false)).isEmpty();
        verify(cache, never()).upsertAll(anyCollection());
    }

    @Test
    void listedWithoutStockIsReturnedWithStockZeroAndCachedNotInStock() {
        // get_part names the part explicitly: the listed part is returned with stock 0 (DESIGN.md 2, stock rule)
        Part listed = part(Distributor.MOUSER, "65-EPC2302").toBuilder().stock(0).build();
        FakeClient mouser = new FakeClient(Distributor.MOUSER) {
            @Override
            public PartLookupResult lookup(String partNumber, Deadline deadline) {
                return PartLookupResult.outOfStock(new PartLookupResult.Identity("65-EPC2302", "EPC", "EPC2302",
                        "GaN FETs EPC eGaN FET,100 V, 1.8 milliohm"), listed);
            }
        };
        when(cache.find(any(), any())).thenReturn(Optional.empty());
        service(mouser);

        PartLookupResponse response = service.lookup(Distributor.MOUSER, "EPC2302", false);

        assertThat(response.found()).isTrue();
        assertThat(response.reason()).isEqualTo(PartLookupResponse.OUT_OF_STOCK);
        assertThat(response.identity().mpn()).isEqualTo("EPC2302");
        assertThat(response.part().partNumber()).isEqualTo("65-EPC2302");
        assertThat(response.part().stock()).isZero();
        assertThat(response.part().prices()).isNotEmpty();
        assertThat(response.part().availability().status()).isEqualTo(Availability.OUT_OF_STOCK);
        assertThat(response.part().availability().note()).isEqualTo(
                "Out of stock at MOUSER; shown because the part number was requested explicitly.");
        // cached with in_stock = false, never as an in-stock part; getPart (in stock only) stays empty
        verify(cache).upsertListed(org.mockito.ArgumentMatchers.argThat((Collection<Part> parts) -> parts.size() == 1
                && parts.iterator().next().distributorPartNumber().equals("65-EPC2302")
                && parts.iterator().next().stock() == 0));
        verify(cache, never()).upsertAll(anyCollection());
        assertThat(service.getPart(Distributor.MOUSER, "EPC2302", false)).isEmpty();
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
        when(cache.find(any(), any())).thenReturn(Optional.empty());
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
        verify(cache, never()).find(eq(Distributor.LCSC), any());
    }

    @Test
    void cachedPartKeepsItsOwnTimestamp() {
        Instant old = NOW.minus(Duration.ofDays(2));
        Part cached = part(Distributor.MOUSER, "M9");
        Part aged = cached.toBuilder().fetchedAt(old).build();
        when(cache.find(any(), any())).thenReturn(Optional.of(aged));
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
        when(cache.find(any(), any())).thenReturn(Optional.empty());
        service(tme);

        assertThat(service.getPart(Distributor.TME, "T0", false)).isPresent();
    }

    /** A Mouser client whose stock refresh answers from a map (DESIGN.md 3.2 "Stock refresh"). */
    static class RefreshingClient extends FakeClient {
        final java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> stock = new java.util.HashMap<>();
        final List<List<String>> refreshed = new java.util.concurrent.CopyOnWriteArrayList<>();

        RefreshingClient() {
            super(Distributor.MOUSER);
        }

        @Override
        public java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> refreshStock(List<String> numbers,
                                                                                           Deadline deadline) {
            refreshed.add(List.copyOf(numbers));
            java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> out = new java.util.HashMap<>();
            numbers.stream().filter(stock::containsKey).forEach(n -> out.put(n, stock.get(n)));
            return out;
        }
    }

    @Test
    void staleCachedPartIsRefreshedBeforeItIsReturned() {
        RefreshingClient mouser = new RefreshingClient();
        Part stale = part(Distributor.MOUSER, "M1").toBuilder().fetchedAt(NOW.minus(Duration.ofHours(30))).build();
        Part fresh = part(Distributor.MOUSER, "M2").toBuilder().fetchedAt(NOW.minus(Duration.ofHours(2))).build();
        when(cache.find(Distributor.MOUSER, "M1")).thenReturn(Optional.of(stale));
        when(cache.find(Distributor.MOUSER, "M2")).thenReturn(Optional.of(fresh));
        mouser.stock.put("M1", new ro.alacrity.kina.distributor.StockUpdate(3, List.of()));
        service(mouser);

        PartLookupResponse refreshed = service.lookup(Distributor.MOUSER, "M1", false);
        PartLookupResponse young = service.lookup(Distributor.MOUSER, "M2", false);

        assertThat(refreshed.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(refreshed.part().stock()).isEqualTo(3);
        assertThat(refreshed.part().stockAsOf()).isEqualTo(NOW);
        assertThat(refreshed.part().availability().status()).isEqualTo("low_stock");
        assertThat(refreshed.part().prices()).hasSize(3);   // no new prices: the cached ones stay
        // get_part defaults to the full attribute set
        assertThat(refreshed.part().extra()).isNotNull();
        assertThat(young.part().stockAsOf()).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(mouser.refreshed).containsExactly(List.of("M1"));   // a part younger than 24 h is not refreshed
        verify(cache).updateStock(List.of(stale.toBuilder().stock(3).fetchedAt(NOW).build()));
        verify(cache, never()).upsertAll(anyCollection());
    }

    @Test
    void cachedPartThatSoldOutIsLookedUpLive() {
        RefreshingClient mouser = new RefreshingClient();
        Part stale = part(Distributor.MOUSER, "M1").toBuilder().fetchedAt(NOW.minus(Duration.ofDays(3))).build();
        when(cache.find(Distributor.MOUSER, "M1")).thenReturn(Optional.of(stale));
        mouser.stock.put("M1", new ro.alacrity.kina.distributor.StockUpdate(0, List.of()));
        service(mouser);

        PartLookupResponse response = service.lookup(Distributor.MOUSER, "M1", false);

        verify(cache).markSoldOut(Distributor.MOUSER, "M1");   // the metadata stays, the row is no longer served
        verify(cache, never()).delete(any(), any());
        assertThat(response.cache()).isEqualTo(CacheStatus.MISS);
        assertThat(response.found()).isFalse();
    }

    /** A Mouser client whose stock refresh and lookup both fail (rate limited, quota gone). */
    static final class DownClient extends RefreshingClient {
        @Override
        public java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> refreshStock(List<String> numbers,
                                                                                           Deadline deadline) {
            refreshed.add(List.copyOf(numbers));
            throw new DistributorException(Distributor.MOUSER, DistributorException.Kind.RATE_LIMITED, "quota");
        }

        @Override
        public ro.alacrity.kina.distributor.PartLookupResult lookup(String partNumber, Deadline deadline) {
            calls.add(new int[] {0, 0});
            throw new DistributorException(Distributor.MOUSER, DistributorException.Kind.RATE_LIMITED, "quota");
        }
    }

    @Test
    void failedRefreshWithinTheTtlServesTheCachedFigures() {
        DownClient mouser = new DownClient();
        Part aged = part(Distributor.MOUSER, "M1").toBuilder().fetchedAt(NOW.minus(Duration.ofDays(2))).build();
        when(cache.find(Distributor.MOUSER, "M1")).thenReturn(Optional.of(aged));
        service(mouser);

        PartLookupResponse response = service.lookup(Distributor.MOUSER, "M1", false);

        assertThat(response.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(response.part().stale()).isNull();
        assertThat(response.part().availability().status()).isNotEqualTo("stale");
        assertThat(mouser.calls).isEmpty();   // no live lookup within the ttl
    }

    @Test
    void failedRefreshAndLookupBeyondTheTtlServeThePartMarkedStale() {
        DownClient mouser = new DownClient();
        Part old = part(Distributor.MOUSER, "M1").toBuilder().fetchedAt(NOW.minus(Duration.ofDays(4))).build();
        when(cache.find(Distributor.MOUSER, "M1")).thenReturn(Optional.of(old));
        service(mouser);

        PartLookupResponse response = service.lookup(Distributor.MOUSER, "M1", false);

        assertThat(response.found()).isTrue();
        assertThat(response.cache()).isEqualTo(CacheStatus.HIT);
        assertThat(response.part().stale()).isTrue();
        assertThat(response.part().availability().status()).isEqualTo("stale");
        assertThat(response.part().stockAsOf()).isEqualTo(NOW.minus(Duration.ofDays(4)));
        assertThat(mouser.refreshed).hasSize(1);
        assertThat(mouser.calls).hasSize(1);   // the live lookup was tried first
    }

    @Test
    void notConfiguredDistributorServesCachedMetadataMarkedStaleBeyondTheTtl() {
        FakeClient tme = new FakeClient(Distributor.TME);
        tme.configured = false;
        Part old = part(Distributor.TME, "T1").toBuilder().fetchedAt(NOW.minus(Duration.ofDays(10))).build();
        when(cache.find(Distributor.TME, "T1")).thenReturn(Optional.of(old));
        service(tme);

        PartLookupResponse response = service.lookup(Distributor.TME, "T1", false);

        assertThat(response.part().stale()).isTrue();
        assertThatThrownBy(() -> service.lookup(Distributor.TME, "unknown", false))
                .isInstanceOfSatisfying(DistributorException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("not_configured"));
    }
}
