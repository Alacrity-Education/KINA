package ro.alacrity.kina.distributor.mouser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Mouser Search API v1 client (DESIGN.md 9.1). Only ships-now stock counts; out-of-stock parts are dropped.
 * Paging across calls and {@code max-pages-per-search} are the orchestrator's job; this client clamps a single
 * page to {@code min(50, kina.distributors.mouser.max-results-per-search)}.
 */
@Component
public class MouserClient implements DistributorClient {

    /** Mouser rejects {@code records} above 50. */
    static final int MAX_PAGE_SIZE = 50;

    private static final Logger log = LoggerFactory.getLogger(MouserClient.class);

    private final KinaProperties.Mouser properties;
    private final MouserApi api;
    private final MouserPartMapper mapper = new MouserPartMapper();
    private final Clock clock;

    @Autowired
    public MouserClient(KinaProperties properties, RestClient.Builder restClientBuilder) {
        this(properties.distributors().mouser(),
                properties.distributors().mouser().isConfigured()
                        ? MouserApi.create(restClientBuilder, properties.distributors().mouser().baseUrl(),
                        properties.distributors().mouser().apiKey().strip())
                        : null,
                Clock.systemUTC());
    }

    MouserClient(KinaProperties.Mouser properties, MouserApi api, Clock clock) {
        this.properties = properties;
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Distributor distributor() {
        return Distributor.MOUSER;
    }

    @Override
    public boolean isConfigured() {
        return api != null && properties.isConfigured();
    }

    /**
     * The records one call really returns ({@code min(50, max-results-per-search)}): the orchestrator advances the raw
     * offset by this amount, so reporting more than {@link #search} fetches would skip records.
     */
    @Override
    public int maxPageSize() {
        return pageLimit();
    }

    @Override
    public DistributorSearchPage search(String query, int offset, int limit) throws DistributorException {
        return guarded(() -> doSearch(query, offset, limit));
    }

    private DistributorSearchPage doSearch(String query, int offset, int limit) {
        MouserApi mouser = requireConfigured();
        int records = Math.min(limit, pageLimit());
        int start = Math.max(0, offset);
        if (query == null || query.isBlank() || records <= 0) {
            return DistributorSearchPage.empty();
        }
        MouserSearchResponse response = mouser.searchByKeyword(query.strip(), records, startingRecord(start));
        MouserSearchResponse.SearchResults results = requireResults(response);
        Instant now = clock.instant();
        List<Part> parts = new ArrayList<>();
        for (MouserPart part : results.parts()) {
            mapper.map(part, now).ifPresent(parts::add);
        }
        int total = results.numberOfResult() == null ? 0 : results.numberOfResult();
        boolean hasMore = !results.parts().isEmpty() && start + results.parts().size() < total;
        log.debug("Mouser search offset={} records={}: {} parts ({} in stock) of {}",
                start, records, results.parts().size(), parts.size(), total);
        return new DistributorSearchPage(parts, total, hasMore);
    }

    @Override
    public Optional<Part> getPart(String distributorPartNumber) throws DistributorException {
        return guarded(() -> doGetPart(distributorPartNumber));
    }

    private Optional<Part> doGetPart(String distributorPartNumber) {
        MouserApi mouser = requireConfigured();
        if (distributorPartNumber == null || distributorPartNumber.isBlank()) {
            return Optional.empty();
        }
        String partNumber = distributorPartNumber.strip();
        if (partNumber.contains("|")) {
            return Optional.empty(); // '|' separates several part numbers in a Mouser part-number search
        }
        List<MouserPart> found = requireResults(mouser.searchByPartNumber(partNumber)).parts();
        Instant now = clock.instant();
        Optional<MouserPart> match = found.stream()
                .filter(p -> partNumber.equalsIgnoreCase(trim(p.mouserPartNumber())))
                .findFirst();
        if (match.isPresent()) {
            return mapper.map(match.get(), now);
        }
        // Several Mouser part numbers may share one MPN: return the first one that is in stock.
        return found.stream()
                .filter(p -> partNumber.equalsIgnoreCase(trim(p.manufacturerPartNumber())))
                .map(p -> mapper.map(p, now))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Mouser's {@code startingRecord} is 1-based: on the live API {@code startingRecord=3, records=2} returned the
     * 3rd and 4th result, and both 0 and 1 return the first one.
     */
    static int startingRecord(int offset) {
        return offset + 1;
    }

    private int pageLimit() {
        int configured = properties.maxResultsPerSearch();
        return configured > 0 ? Math.min(configured, MAX_PAGE_SIZE) : MAX_PAGE_SIZE;
    }

    private MouserApi requireConfigured() {
        if (!isConfigured()) {
            throw DistributorException.notConfigured(Distributor.MOUSER);
        }
        return api;
    }

    private static MouserSearchResponse.SearchResults requireResults(MouserSearchResponse response) {
        if (response == null || response.searchResults() == null) {
            throw new DistributorException(Distributor.MOUSER, Kind.BAD_RESPONSE, "response has no SearchResults");
        }
        return response.searchResults();
    }

    /** Guarantees that nothing but a {@link DistributorException} leaves the client. */
    private static <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (DistributorException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DistributorException(Distributor.MOUSER, Kind.BAD_RESPONSE,
                    "unexpected response: " + e.getClass().getSimpleName() + ": " + MouserApi.mask(e.getMessage()));
        }
    }

    private static String trim(String s) {
        return s == null ? null : s.strip();
    }
}
