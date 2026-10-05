package ro.alacrity.kina.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import ro.alacrity.kina.TestcontainersConfiguration;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.domain.BatchSearchRequest;
import ro.alacrity.kina.domain.BatchSearchResponse;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.DistributorStatusService;
import ro.alacrity.kina.search.PartLookupService;
import ro.alacrity.kina.search.PartSearchService;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.domain.ParsedQueryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** REST API (DESIGN.md section 5) with mocked services: request mapping, JSON shape and RFC 9457 problem details. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class PartsApiTest {

    static final MediaType PROBLEM = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    RestTestClient client;

    @MockitoBean
    PartSearchService searchService;

    @MockitoBean
    PartLookupService lookupService;

    @MockitoBean
    DistributorStatusService statusService;

    static Part part() {
        return new Part(Distributor.TME, "CL21B106KPQNNNE", "SAMSUNG", "CL21B106KPQNNNE", "MLCC 10uF 25V X7R 0805",
                "Capacitors", "0805", 1200, 10, 10,
                List.of(new PriceBreak(10, new BigDecimal("0.05"), "EUR"),
                        new PriceBreak(100, new BigDecimal("0.03"), "EUR"),
                        new PriceBreak(1000, new BigDecimal("0.02"), "EUR"),
                        new PriceBreak(5000, new BigDecimal("0.01"), "EUR")),
                null, "https://example.invalid/p.jpg", "https://www.tme.eu/en/details/CL21B106KPQNNNE/",
                Map.of("Capacitance", "10uF"), Map.of("product_status", List.of()), Instant.now());
    }

    static SearchResponse response(String query) {
        return new SearchResponse(query, ParsedQueryResponse.from(new QueryParser().parse(query)), RankingMode.BLENDED,
                null, List.of(new DistributorResult(Distributor.TME, 77, 40, 1, CacheStatus.HIT, null,
                List.of(PartResponse.from(part(), 1, 0.93)))));
    }

    @Test
    void searchMapsParametersCaseInsensitivelyAndReturnsSnakeCase() {
        when(searchService.search(any())).thenAnswer(inv -> response(((SearchRequest) inv.getArgument(0)).query()));

        String body = client.get().uri("/api/v1/parts/search?q=10uF X7R 0805&max_results=5&distributors=tme,Lcsc"
                        + "&bypass_cache=true")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .returnResult(String.class).getResponseBody();

        verify(searchService).search(new SearchRequest("10uF X7R 0805", 5, Set.of(Distributor.TME, Distributor.LCSC),
                true));
        assertThat(body).contains("\"ranking\":\"blended\"", "\"total_results\":77", "\"cache\":\"hit\"",
                "\"part_number\":\"CL21B106KPQNNNE\"", "\"min_order_qty\":10", "\"ranking_note\":null");
        assertThat(body).contains("{\"qty\":10,\"unit_price\":0.05,\"currency\":\"EUR\"}")
                .doesNotContain("\"qty\":5000");
    }

    @Test
    void searchReportsTheDistributorQueryAndTheParsedConnector() {
        String query = "90 degree dupont style female pin header 90 degree THT pins 6 position";
        when(searchService.search(any())).thenReturn(new SearchResponse(query,
                ParsedQueryResponse.from(new QueryParser().parse(query)), RankingMode.FALLBACK, "cross-encoder disabled",
                List.of(new DistributorResult(Distributor.TME, 166, 40, 1, CacheStatus.MISS, null,
                        List.of(PartResponse.from(part(), 1, 0.93)), "pin strips female 6", 0,
                        "pin strips female 6 angled"))));

        String body = client.get().uri("/api/v1/parts/search?q=" + query)
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseBody();

        assertThat(body).contains("\"distributor_query\":\"pin strips female 6 angled\"",
                "\"fallback_query\":\"pin strips female 6\"", "\"family\":\"connector\"",
                "\"connector\":{\"type\":\"female header\",\"gender\":\"female\",\"positions\":6,"
                        + "\"pitch\":\"2.54mm\",\"orientation\":\"right angle\"}", "\"mounting\":\"THT\"");
    }

    @Test
    void searchDefaultsAndRepeatedDistributorParameters() {
        when(searchService.search(any())).thenAnswer(inv -> response(((SearchRequest) inv.getArgument(0)).query()));

        client.get().uri("/api/v1/parts/search?q=4k7&distributors=MOUSER&distributors=lcsc")
                .exchange().expectStatus().isOk();

        verify(searchService).search(new SearchRequest("4k7", 10, Set.of(Distributor.MOUSER, Distributor.LCSC),
                false));
    }

    @Test
    void searchValidationErrorsAreProblemDetails() {
        client.get().uri("/api/v1/parts/search?q=10uF&max_results=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.type").isEqualTo("urn:kina:problem:validation")
                .jsonPath("$.title").isEqualTo("Invalid request")
                .jsonPath("$.instance").isEqualTo("/api/v1/parts/search")
                .jsonPath("$.errors[0].field").isEqualTo("max_results");

        client.get().uri("/api/v1/parts/search?q={q}", "  ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("q");

        client.get().uri("/api/v1/parts/search")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody().jsonPath("$.status").isEqualTo(400);

        client.get().uri("/api/v1/parts/search?q=10uF&distributors=digikey")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:kina:problem:unknown-distributor")
                .jsonPath("$.detail").isEqualTo("Unknown distributor 'digikey'; expected one of LCSC, TME, MOUSER");
        verify(searchService, never()).search(any());
    }

    @Test
    void batchAcceptsSnakeCaseBodyAndValidatesIt() {
        when(searchService.searchBatch(any())).thenAnswer(inv -> new BatchSearchResponse(
                ((BatchSearchRequest) inv.getArgument(0)).queries().stream().map(q -> response(q.query())).toList()));

        client.post().uri("/api/v1/parts/search/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"queries":[{"query":"10uF X7R 0805","max_results":3},{"query":"4k7 0603"}],
                         "distributors":["lcsc","MOUSER"],"bypass_cache":true}""")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.results.length()").isEqualTo(2)
                .jsonPath("$.results[1].query").isEqualTo("4k7 0603");
        verify(searchService).searchBatch(BatchSearchRequest.of(List.of(
                        new SearchRequest("10uF X7R 0805", 3, Set.of(), false),
                        new SearchRequest("4k7 0603", 10, Set.of(), false)),
                Set.of(Distributor.LCSC, Distributor.MOUSER), true));

        client.post().uri("/api/v1/parts/search/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"queries\":[{\"query\":\"\",\"max_results\":99}]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.type").isEqualTo("urn:kina:problem:validation")
                .jsonPath("$.errors.length()").isEqualTo(2);

        client.post().uri("/api/v1/parts/search/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"queries\":[]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].field").isEqualTo("queries");

        client.post().uri("/api/v1/parts/search/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"queries\":[{\"query\":\"x\"}],\"distributors\":[\"farnell\"]}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody().jsonPath("$.type").isEqualTo("urn:kina:problem:unknown-distributor");

        client.post().uri("/api/v1/parts/search/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM);
    }

    @Test
    void getPartFoundNotFoundAndSlashesInPartNumbers() {
        when(lookupService.getPart(Distributor.TME, "CL21B106KPQNNNE", false))
                .thenReturn(Optional.of(PartResponse.from(part())));
        when(lookupService.getPart(eq(Distributor.TME), eq("ABC/1"), anyBoolean())).thenReturn(Optional.empty());

        client.get().uri("/api/v1/parts/tme/CL21B106KPQNNNE")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.part_number").isEqualTo("CL21B106KPQNNNE")
                .jsonPath("$.rank").isEmpty()
                .jsonPath("$.prices.length()").isEqualTo(3);

        client.get().uri("/api/v1/parts/TME/ABC/1?bypass_cache=true")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.type").isEqualTo("urn:kina:problem:not-found")
                .jsonPath("$.distributor").isEqualTo("TME")
                .jsonPath("$.part_number").isEqualTo("ABC/1");
        verify(lookupService).getPart(Distributor.TME, "ABC/1", true);

        client.get().uri("/api/v1/parts/arrow/X1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo("urn:kina:problem:unknown-distributor");
    }

    @Test
    void distributorFailuresAndUnexpectedErrorsHideInternals() {
        when(lookupService.getPart(Distributor.MOUSER, "M1", false)).thenThrow(
                new DistributorException(Distributor.MOUSER, DistributorException.Kind.RATE_LIMITED, "secret-ish"));
        when(lookupService.getPart(Distributor.MOUSER, "M2", false)).thenThrow(
                DistributorException.notConfigured(Distributor.MOUSER));
        when(searchService.search(any())).thenThrow(new IllegalStateException("internal detail /etc/passwd"));

        client.get().uri("/api/v1/parts/mouser/M1")
                .exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.error").isEqualTo("rate_limited")
                .jsonPath("$.detail").isEqualTo("MOUSER lookup failed: rate_limited");
        client.get().uri("/api/v1/parts/mouser/M2")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("not_configured");

        String body = client.get().uri("/api/v1/parts/search?q=x")
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .returnResult(String.class).getResponseBody();
        assertThat(body).contains("\"title\":\"Internal server error\"", "\"status\":500")
                .doesNotContain("passwd", "IllegalStateException", "trace", "at ro.");
    }

    @Test
    void distributorsEndpoint() {
        when(statusService.status()).thenReturn(new DistributorStatusResponse(List.of(
                new DistributorStatusResponse.DistributorStatus(Distributor.LCSC, true, false,
                        "JLCPCB parts database not downloaded yet", false, null, 200, null)),
                null, new DistributorStatusResponse.RankingSummary("fallback", true, false,
                        "cross-encoder/ms-marco-MiniLM-L6-v2", "int8", null, "/data/cross-encoder", 4, null,
                        "cross-encoder model not loaded yet", 40, 0.5, "PT5S")));

        client.get().uri("/api/v1/distributors")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.distributors[0].distributor").isEqualTo("LCSC")
                .jsonPath("$.distributors[0].uses_cache").isEqualTo(false)
                .jsonPath("$.distributors[0].max_results_per_search").isEqualTo(200)
                .jsonPath("$.ranking.cross_encoder_enabled").isEqualTo(true)
                .jsonPath("$.ranking.mode").isEqualTo("fallback")
                .jsonPath("$.ranking.ready").isEqualTo(false)
                .jsonPath("$.ranking.model_variant").isEqualTo("int8")
                .jsonPath("$.ranking.weight").isEqualTo(0.5)
                .jsonPath("$.ranking.max_candidates").isEqualTo(40);
    }
}
