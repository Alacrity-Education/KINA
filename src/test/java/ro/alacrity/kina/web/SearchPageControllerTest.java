package ro.alacrity.kina.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ro.alacrity.kina.cache.CacheStatus;
import ro.alacrity.kina.domain.BelowSpecPart;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.domain.RankingMode;
import ro.alacrity.kina.domain.ResponseDetail;
import ro.alacrity.kina.domain.SearchRequest;
import ro.alacrity.kina.domain.SearchResponse;
import ro.alacrity.kina.search.PartSearchService;
import ro.alacrity.kina.search.QueryParser;
import ro.alacrity.kina.security.DevModeIntegrationTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** The Search tab (DESIGN.md 6, "Web UI") with a stubbed {@link PartSearchService}. */
@DevModeIntegrationTest
class SearchPageControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    PartSearchService searchService;

    static Part part(String description, String productUrl) {
        return new Part(Distributor.TME, "RC0603FR-0710KL", "YAGEO", "RC0603FR-0710KL", description,
                "Resistors", "0603", 12_345, 100, 100,
                List.of(new PriceBreak(100, new BigDecimal("0.0040"), "EUR"),
                        new PriceBreak(1000, new BigDecimal("0.0020"), "EUR"),
                        new PriceBreak(5000, new BigDecimal("0.0015"), "EUR"),
                        new PriceBreak(10000, new BigDecimal("0.0010"), "EUR")),
                "https://example.invalid/ds.pdf", null, productUrl,
                Map.of("Resistance", "10kΩ"), Map.of(), Instant.now());
    }

    static SearchResponse response(String query, Part part) {
        PartResponse p = PartResponse.of(part, 1, 0.9, 1.0, 1, ResponseDetail.COMPACT,
                Map.of("resistance", "10kΩ", "package", "0603"));
        DistributorResult tme = DistributorResult.builder().distributor(Distributor.TME).totalResults(1234)
                .fetched(40).returned(1).cache(CacheStatus.MISS).parts(List.of(p))
                .excludedByConstraints(7).excludedByConstraintsDetail(Map.of("package", 7))
                .excludedBelowSpec(2).excludedBelowSpecDetail(List.of(
                        new BelowSpecPart("RC0603-LOW", "RC0603-LOW", "power", "0.05W", "0.1W")))
                .outOfStockMatches(3).exactMatches(1).distributorQuery("resistor 10k 0603").build();
        DistributorResult mouser = DistributorResult.failed(Distributor.MOUSER, "rate_limited",
                CacheStatus.MISS);
        return new SearchResponse(query, ParsedQueryResponse.from(new QueryParser().parse(query)),
                RankingMode.FALLBACK, "cross-encoder not loaded", List.of(tme, mouser));
    }

    @Test
    void formWithoutQueryRunsNoSearch() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("name=\"max_results\"", "name=\"quantity\"", "name=\"detail\"", "name=\"bypass_cache\"",
                        "name=\"allow_below_spec\"", "value=\"LCSC\"", "value=\"TME\"", "value=\"MOUSER\"")
                .doesNotContain("part-row");
        verify(searchService, never()).search(any());
    }

    @Test
    void searchRendersTheCountsAndThePartsTable() throws Exception {
        when(searchService.search(any(), anyBoolean())).thenReturn(response("10k 0603 resistor",
                part("Resistor 10k 0603", "https://www.tme.eu/en/details/RC0603FR-0710KL/")));

        MockHttpServletResponse response = mvc.perform(get("/").queryParam("q", " 10k 0603 resistor ")
                        .queryParam("distributors", "TME", "mouser").queryParam("max_results", "5")
                        .queryParam("quantity", "250").queryParam("detail", "compact")
                        .queryParam("bypass_cache", "true"))
                .andReturn().getResponse();

        verify(searchService).search(new SearchRequest("10k 0603 resistor", 5,
                Set.of(Distributor.TME, Distributor.MOUSER), true, 250, ResponseDetail.COMPACT, false), true);
        assertThat(response.getStatus()).isEqualTo(200);
        String page = response.getContentAsString();
        assertThat(page)
                .contains("class=\"part-row\"")
                .contains("href=\"https://www.tme.eu/en/details/RC0603FR-0710KL/\"")
                .contains("href=\"https://example.invalid/ds.pdf\"")
                .contains("RC0603FR-0710KL", "YAGEO", "Resistor 10k 0603", "12,345")
                .contains("100+: 0.0040 EUR", "1000+: 0.0020 EUR", "5000+: 0.0015 EUR")
                .doesNotContain("10000+: 0.0010 EUR")
                .contains("resistance: 10kΩ")
                .contains("1 returned of 40 fetched (1,234 reported by the distributor)")
                .contains("package: 7", "RC0603-LOW power 0.05W &lt; 0.1W")
                .contains("cache miss", "error: rate_limited")
                .contains("resistor 10k 0603")
                .contains("fallback", "(cross-encoder not loaded)")
                .contains("family: resistor")
                .containsPattern("<input type=\"checkbox\" name=\"distributors\" value=\"TME\" checked")
                .containsPattern("<input type=\"checkbox\" name=\"distributors\" value=\"LCSC\">")
                .containsPattern("<option value=\"5\" selected")
                .contains("value=\"250\"");
    }

    @Test
    void distributorTextIsEscapedAndOnlyHttpLinksAreRendered() throws Exception {
        when(searchService.search(any(), anyBoolean())).thenReturn(response("10k resistor",
                part("<script>alert('x')</script> 10k", "javascript:alert(1)")));

        String page = mvc.perform(get("/").queryParam("q", "10k resistor")).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt; 10k")
                .doesNotContain("<script>alert(")
                .doesNotContain("javascript:alert");
    }

    static SearchResponse single(Distributor distributor, String photoUrl) {
        Part part = part("MLCC 10uF 25V X7R 0805", "https://example.invalid/p").toBuilder()
                .distributor(distributor).manufacturerPartNumber("CL21B106KAYQNNE").build();
        PartResponse p = PartResponse.of(part, 1, 0.9, 1.0, 1, ResponseDetail.COMPACT, Map.of())
                .toBuilder().photoUrl(photoUrl).build();
        DistributorResult r = DistributorResult.builder().distributor(distributor).fetched(1).returned(1)
                .cache(CacheStatus.HIT).parts(List.of(p)).build();
        return new SearchResponse("10uF X7R 0805", ParsedQueryResponse.from(new QueryParser().parse("10uF X7R 0805")),
                RankingMode.BLENDED, null, List.of(r));
    }

    @Test
    void aDistributorPhotoIsLinkedAsTheRowThumbnail() throws Exception {
        when(searchService.search(any(), anyBoolean())).thenReturn(single(Distributor.MOUSER,
                "//www.mouser.com/images/samsung/images/MLCC_0805.jpg"));

        String page = mvc.perform(get("/").queryParam("q", "10uF X7R 0805")).andReturn().getResponse()
                .getContentAsString();

        verify(searchService).search(any(), eq(true));
        assertThat(page).contains("<th class=\"thumb\"></th>").doesNotContain("example.jpg");
        Matcher img = Pattern.compile("<td class=\"thumb\"><img[^>]*></td>")
                .matcher(page);
        assertThat(img.find()).isTrue();
        assertThat(img.group())
                .contains("src=\"https://www.mouser.com/images/samsung/images/MLCC_0805.jpg\"",
                        "alt=\"CL21B106KAYQNNE\"", "loading=\"lazy\"", "decoding=\"async\"",
                        "referrerpolicy=\"no-referrer\"")
                .doesNotContain("width=", "height=");
    }

    @Test
    void aPartWithoutPhotoHasNoThumbnail() throws Exception {
        when(searchService.search(any(), anyBoolean())).thenReturn(single(Distributor.LCSC, null));

        String page = mvc.perform(get("/").queryParam("q", "10uF X7R 0805")).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).contains("class=\"part-row\"", "CL21B106KAYQNNE")
                .doesNotContain("<img", "class=\"thumb\"");
    }

    @Test
    void onlyHttpPhotosAreRendered() throws Exception {
        when(searchService.search(any(), anyBoolean())).thenReturn(single(Distributor.TME, "javascript:alert(1)"));

        String page = mvc.perform(get("/").queryParam("q", "10uF X7R 0805")).andReturn().getResponse()
                .getContentAsString();

        assertThat(page).doesNotContain("<img", "javascript:alert", "class=\"thumb\"");
    }

    @Test
    void blankQueryAndBadValuesRenderTheError() throws Exception {
        MockHttpServletResponse blank = mvc.perform(get("/").queryParam("q", "   ")).andReturn().getResponse();
        assertThat(blank.getStatus()).isEqualTo(400);
        assertThat(blank.getContentAsString()).contains("class=\"error\"", "Please enter what you are looking for");

        MockHttpServletResponse unknown = mvc.perform(get("/").queryParam("q", "10k")
                .queryParam("distributors", "digikey")).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(400);
        assertThat(unknown.getContentAsString()).contains("class=\"error\"").contains("digikey");

        MockHttpServletResponse quantity = mvc.perform(get("/").queryParam("q", "10k")
                .queryParam("quantity", "lots")).andReturn().getResponse();
        assertThat(quantity.getStatus()).isEqualTo(400);
        assertThat(quantity.getContentAsString()).contains("quantity must be a whole number");
        verify(searchService, never()).search(any());
    }

    @Test
    void aFailingSearchRendersTheError() throws Exception {
        when(searchService.search(any(), anyBoolean())).thenThrow(new IllegalStateException("boom"));
        MockHttpServletResponse response = mvc.perform(get("/").queryParam("q", "10k")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("The search failed").doesNotContain("boom");
    }
}
