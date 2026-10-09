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
import ro.alacrity.kina.domain.PartLookupResponse;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.DistributorStatusService;
import ro.alacrity.kina.search.PartLookupService;
import ro.alacrity.kina.search.PartSearchService;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.domain.ParsedQueryResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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

    @org.springframework.boot.test.web.server.LocalServerPort
    int port;

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
                null, List.of(DistributorResult.builder().distributor(Distributor.TME).totalResults(77).fetched(40)
                        .returned(1).cache(CacheStatus.HIT).parts(List.of(PartResponse.from(part(), 1, 0.93))).build()));
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
                List.of(DistributorResult.builder().distributor(Distributor.TME).totalResults(166).fetched(40)
                        .returned(1).cache(CacheStatus.MISS).parts(List.of(PartResponse.from(part(), 1, 0.93)))
                        .fallbackQuery("pin strips female 6").distributorQuery("pin strips female 6 angled").build())));

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
    void allowBelowSpecReachesTheSearch() {
        when(searchService.search(any())).thenAnswer(inv -> response(((SearchRequest) inv.getArgument(0)).query()));

        client.get().uri("/api/v1/parts/search?q=22uF 25V 1206&allow_below_spec=true").exchange().expectStatus().isOk();

        verify(searchService).search(new SearchRequest("22uF 25V 1206", 10, Set.of(), false, 1,
                ro.alacrity.kina.domain.ResponseDetail.COMPACT, true));
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
        when(lookupService.lookup(Distributor.TME, "CL21B106KPQNNNE", false, 1, ResponseDetail.FULL))
                .thenReturn(PartLookupResponse.found(Distributor.TME, "CL21B106KPQNNNE", CacheStatus.MISS,
                        PartResponse.from(part())));
        when(lookupService.lookup(eq(Distributor.TME), eq("ABC/1"), anyBoolean(), anyInt(), any()))
                .thenReturn(PartLookupResponse.notFound(Distributor.TME, "ABC/1", CacheStatus.BYPASSED, null));

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
                .jsonPath("$.part_number").isEqualTo("ABC/1")
                .jsonPath("$.reason").isEqualTo("not_found")
                .jsonPath("$.identity").doesNotExist();
        // get_part defaults to the full attribute set (DESIGN.md 4)
        verify(lookupService).lookup(Distributor.TME, "ABC/1", true, 1, ResponseDetail.FULL);

        client.get().uri("/api/v1/parts/arrow/X1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo("urn:kina:problem:unknown-distributor");
    }

    @Test
    void lookupByQueryParametersAcceptsEncodedSlashes() {
        when(lookupService.lookup(Distributor.TME, "DTMSS-20/0.010/20V", false, 1, ResponseDetail.FULL))
                .thenReturn(PartLookupResponse.found(Distributor.TME, "DTMSS-20/0.010/20V", CacheStatus.HIT,
                        PartResponse.from(part())));
        when(lookupService.lookup(Distributor.TME, "ABC/1", true, 5, ResponseDetail.FULL))
                .thenReturn(PartLookupResponse.notFound(Distributor.TME, "ABC/1", CacheStatus.BYPASSED, null));

        // a URI variable is encoded strictly: the request carries part_number=DTMSS-20%2F0.010%2F20V
        client.get().uri("/api/v1/parts/lookup?distributor=TME&part_number={pn}", "DTMSS-20/0.010/20V")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.part_number").isEqualTo("CL21B106KPQNNNE")
                .jsonPath("$.prices.length()").isEqualTo(3);
        verify(lookupService).lookup(Distributor.TME, "DTMSS-20/0.010/20V", false, 1, ResponseDetail.FULL);

        client.get().uri("/api/v1/parts/lookup?distributor=tme&part_number=ABC/1&bypass_cache=true&quantity=5")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.part_number").isEqualTo("ABC/1")
                .jsonPath("$.reason").isEqualTo("not_found");

        client.get().uri("/api/v1/parts/lookup?distributor=arrow&part_number=X1")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.type").isEqualTo("urn:kina:problem:unknown-distributor");
        client.get().uri("/api/v1/parts/lookup?distributor=TME")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM);
        client.get().uri("/api/v1/parts/lookup?distributor=TME&part_number={pn}", " ")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM);
    }

    /**
     * Part numbers with characters a path cannot carry plainly (77 cached parts of the production cache hold {@code %}
     * or a backslash, validation 2026-10-09): percent-encoded in the path, and in the query-parameter form
     * {@code /api/v1/parts/{distributor}?part_number=}. The request URI is sent exactly as written (no re-encoding).
     */
    @Test
    void awkwardPartNumbersWorkPercentEncodedInThePathAndAsAQueryParameter() {
        List<String> numbers = List.of("RC0603FR-07100%", "ABC\\1", "DTMSS-20/0.010/20V", "A+B", "LM 317 T",
                "50% 1/4W\\X+Y");
        for (String number : numbers) {
            when(lookupService.lookup(Distributor.TME, number, false, 1, ResponseDetail.FULL))
                    .thenReturn(PartLookupResponse.found(Distributor.TME, number, CacheStatus.HIT,
                            PartResponse.from(part())));
        }
        List<String> failed = new ArrayList<>();
        for (String number : numbers) {
            String encoded = percentEncoded(number);
            for (String path : List.of("/api/v1/parts/TME/" + encoded, "/api/v1/parts/TME?part_number=" + encoded,
                    "/api/v1/parts/lookup?distributor=TME&part_number=" + encoded)) {
                var result = client.get().uri(java.net.URI.create("http://localhost:" + port + path))
                        .exchange().expectBody(String.class).returnResult();
                if (result.getStatus().value() != 200
                        || !String.valueOf(result.getResponseBody()).contains("\"CL21B106KPQNNNE\"")) {
                    failed.add(path + " -> " + result.getStatus().value());
                }
            }
        }
        assertThat(failed).isEmpty();
        // a plus sign in a path is a plus sign; in a query string it is a space, so it is encoded there (%2B)
        verify(lookupService, org.mockito.Mockito.times(3)).lookup(Distributor.TME, "A+B", false, 1,
                ResponseDetail.FULL);
        when(lookupService.lookup(Distributor.TME, "RC0603FR-07100%", true, 5, ResponseDetail.FULL))
                .thenReturn(PartLookupResponse.notFound(Distributor.TME, "RC0603FR-07100%", CacheStatus.BYPASSED,
                        null));
        client.get().uri(java.net.URI.create("http://localhost:" + port + "/api/v1/parts/TME/RC0603FR-07100%25"
                        + "?bypass_cache=true&quantity=5"))
                .exchange()
                .expectStatus().isNotFound();
        verify(lookupService).lookup(Distributor.TME, "RC0603FR-07100%", true, 5, ResponseDetail.FULL);
        client.get().uri(java.net.URI.create("http://localhost:" + port + "/api/v1/parts/TME?part_number=%20"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(PROBLEM);
        // only the part path accepts the encodings: every other path stays strict
        for (String path : List.of("/api/v1/distributors%2Fx", "/api/v1/distributors%5Cx", "/api/v1/distributors%25")) {
            client.get().uri(java.net.URI.create("http://localhost:" + port + path))
                    .exchange()
                    .expectStatus().isBadRequest();
        }
    }

    /** Every character but the unreserved ones percent-encoded (UTF-8), a space as {@code %20}. */
    private static String percentEncoded(String value) {
        StringBuilder out = new StringBuilder();
        for (byte b : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (Character.isLetterOrDigit(c) && c < 128 || "-._~".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    @Test
    void getPartOutOfStockIs404WithReasonAndIdentity() {
        when(lookupService.lookup(Distributor.MOUSER, "ERA6AEB5361V", false, 1, ResponseDetail.FULL))
                .thenReturn(PartLookupResponse.outOfStock(Distributor.MOUSER, "ERA6AEB5361V", CacheStatus.MISS,
                        new PartLookupResponse.Identity("667-ERA-6AEB5361V", "Panasonic", "ERA-6AEB5361V",
                                "Thin Film Resistors - SMD 0805 5.36Kohm 0.1% 25ppm")));

        client.get().uri("/api/v1/parts/MOUSER/ERA6AEB5361V")
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(PROBLEM)
                .expectBody()
                .jsonPath("$.reason").isEqualTo("out_of_stock")
                .jsonPath("$.identity.part_number").isEqualTo("667-ERA-6AEB5361V")
                .jsonPath("$.identity.mpn").isEqualTo("ERA-6AEB5361V")
                .jsonPath("$.identity.manufacturer").isEqualTo("Panasonic")
                .jsonPath("$.detail").isEqualTo(
                        "Part ERA6AEB5361V is listed at MOUSER but has no stock that ships now");
    }

    @Test
    void distributorFailuresAndUnexpectedErrorsHideInternals() {
        when(lookupService.lookup(Distributor.MOUSER, "M1", false, 1, ResponseDetail.FULL)).thenThrow(
                new DistributorException(Distributor.MOUSER, DistributorException.Kind.RATE_LIMITED, "secret-ish"));
        when(lookupService.lookup(Distributor.MOUSER, "M2", false, 1, ResponseDetail.FULL)).thenThrow(
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
