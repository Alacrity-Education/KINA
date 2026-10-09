package ro.alacrity.kina.distributor.mouser;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.ApiQuotaTracker;
import ro.alacrity.kina.distributor.Deadline;
import ro.alacrity.kina.distributor.DistributorClient;
import ro.alacrity.kina.distributor.DistributorException;
import ro.alacrity.kina.distributor.DistributorException.Kind;
import ro.alacrity.kina.distributor.DistributorSearchPage;
import ro.alacrity.kina.distributor.PartLookupResult;
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
@Slf4j
public class MouserClient implements DistributorClient {

    /** Mouser rejects {@code records} above 50. */
    static final int MAX_PAGE_SIZE = 50;

    @Autowired private KinaProperties properties;
    @Autowired private RestClient.Builder restClientBuilder;
    /** Counts the HTTP requests against the quota (DESIGN.md 3.7); null when built without Spring. */
    @Autowired private ApiQuotaTracker quota;
    private final MouserPartMapper mapper = new MouserPartMapper();
    private Clock clock = Clock.systemUTC();
    private KinaProperties.Mouser config;
    /** Built from the configuration unless a test supplied one. */
    private MouserApi api;

    @PostConstruct
    void init() {
        config = properties.distributors().mouser();
        if (api == null && config.isConfigured()) {
            api = MouserApi.create(restClientBuilder, config.baseUrl(), config.apiKey().strip());
        }
        if (api != null && quota != null) {
            api.quota(quota);
        }
    }

    @Override
    public Distributor distributor() {
        return Distributor.MOUSER;
    }

    @Override
    public boolean isConfigured() {
        return api != null && config.isConfigured();
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
        return search(query, offset, limit, Deadline.immediate());
    }

    /** Waits for Mouser rate limits (HTTP 429, {@code TooManyRequests}) within {@code deadline} (DESIGN.md 3.6). */
    @Override
    public DistributorSearchPage search(String query, int offset, int limit, Deadline deadline)
            throws DistributorException {
        return guarded(() -> doSearch(query, offset, limit, deadline));
    }

    private DistributorSearchPage doSearch(String query, int offset, int limit, Deadline deadline) {
        MouserApi mouser = requireConfigured();
        int records = Math.min(limit, pageLimit());
        int start = Math.max(0, offset);
        if (query == null || query.isBlank() || records <= 0) {
            return DistributorSearchPage.empty();
        }
        MouserSearchResponse response = mouser.searchByKeyword(query.strip(), records, startingRecord(start), deadline);
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
        int outOfStock = (int) results.parts().stream()
                .filter(p -> p != null && p.mouserPartNumber() != null && !p.mouserPartNumber().isBlank())
                .count() - parts.size();
        return new DistributorSearchPage(parts, total, hasMore, outOfStock);
    }

    @Override
    public Optional<Part> getPart(String distributorPartNumber) throws DistributorException {
        return getPart(distributorPartNumber, Deadline.immediate());
    }

    @Override
    public Optional<Part> getPart(String distributorPartNumber, Deadline deadline) throws DistributorException {
        return lookup(distributorPartNumber, deadline).asOptional();
    }

    /**
     * One {@code /search/partnumber} call ({@code Exact}). Mouser normalises the spelling itself (verified live
     * 2026-10-05: {@code ERA6AEB5361V} returns {@code 667-ERA-6AEB5361V} with MPN {@code ERA-6AEB5361V}), so the
     * answer is matched by Mouser part number, then by MPN, both compared with {@link PartLookupResult#normalize}.
     * Several Mouser part numbers may share one MPN: the first one in stock wins; a matching part without ships-now
     * stock ({@code AvailabilityInStock} 0 or missing) is {@code OUT_OF_STOCK}, also a catalogue part Mouser does not sell
     * (part number {@code N/A}; its identity then has no part number); it carries the listed part (stock 0) of the first
     * match that has a Mouser part number.
     */
    @Override
    public PartLookupResult lookup(String partNumber, Deadline deadline) throws DistributorException {
        return guarded(() -> doLookup(partNumber, deadline));
    }

    private PartLookupResult doLookup(String distributorPartNumber, Deadline deadline) {
        MouserApi mouser = requireConfigured();
        if (distributorPartNumber == null || distributorPartNumber.isBlank()) {
            return PartLookupResult.notFound();
        }
        String partNumber = distributorPartNumber.strip();
        if (partNumber.contains("|")) {
            return PartLookupResult.notFound(); // '|' separates several part numbers in a Mouser part-number search
        }
        // spellings with spaces ("HCMA0703 2R2 R") are tried as HCMA0703-2R2-R, then HCMA07032R2R
        List<MouserPart> found = List.of();
        for (String variant : PartLookupResult.variants(partNumber)) {
            found = requireResults(mouser.searchByPartNumber(variant, deadline)).parts();
            if (found.stream().anyMatch(p -> PartLookupResult.samePartNumber(partNumber, p.mouserPartNumber())
                    || PartLookupResult.samePartNumber(partNumber, p.manufacturerPartNumber()))) {
                break;
            }
        }
        List<MouserPart> matches = new ArrayList<>();
        found.stream().filter(p -> PartLookupResult.samePartNumber(partNumber, p.mouserPartNumber()))
                .forEach(matches::add);
        found.stream().filter(p -> !matches.contains(p)
                        && PartLookupResult.samePartNumber(partNumber, p.manufacturerPartNumber()))
                .forEach(matches::add);
        Instant now = clock.instant();
        for (MouserPart p : matches) {
            Optional<Part> part = mapper.map(p, now);
            if (part.isPresent()) {
                return PartLookupResult.found(part.get());
            }
        }
        // Mouser lists catalogue parts it does not sell with MouserPartNumber "N/A" (live: ERA-6ARB5361V); the listed
        // part (stock 0) is the first match with a real Mouser part number
        Part listed = matches.stream().map(p -> mapper.mapListed(p, now)).flatMap(Optional::stream).findFirst()
                .orElse(null);
        return matches.stream().findFirst()
                .map(p -> PartLookupResult.outOfStock(new PartLookupResult.Identity(mouserNumber(p.mouserPartNumber()),
                        blankToNull(p.manufacturer()), blankToNull(p.manufacturerPartNumber()),
                        blankToNull(p.description())), listed))
                .orElseGet(PartLookupResult::notFound);
    }

    /** Part numbers per {@code /search/partnumber} call (Mouser accepts up to 10 joined with {@code |}). */
    static final int PART_NUMBERS_PER_CALL = 10;

    /**
     * Current stock and prices: {@code /search/partnumber} with up to {@value #PART_NUMBERS_PER_CALL} Mouser part
     * numbers joined by {@code |} per call (verified live 2026-10-06 with {@code Exact}), matched by Mouser part number.
     * A listed part without ships-now stock reports stock 0.
     */
    @Override
    public java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> refreshStock(List<String> partNumbers,
                                                                                       Deadline deadline) {
        return guarded(() -> {
            MouserApi mouser = requireConfigured();
            List<String> wanted = partNumbers == null ? List.of() : partNumbers.stream()
                    .filter(s -> s != null && !s.isBlank() && !s.contains("|")).map(String::strip).distinct().toList();
            java.util.Map<String, ro.alacrity.kina.distributor.StockUpdate> out = new java.util.HashMap<>();
            Instant now = clock.instant();
            for (int from = 0; from < wanted.size(); from += PART_NUMBERS_PER_CALL) {
                List<String> batch = wanted.subList(from, Math.min(wanted.size(), from + PART_NUMBERS_PER_CALL));
                for (MouserPart p : requireResults(mouser.searchByPartNumber(String.join("|", batch), deadline))
                        .parts()) {
                    String number = p.mouserPartNumber() == null ? null : p.mouserPartNumber().strip();
                    String key = batch.stream().filter(b -> b.equalsIgnoreCase(number)).findFirst().orElse(null);
                    if (key == null) {
                        continue;
                    }
                    out.putIfAbsent(key, mapper.map(p, now)
                            .map(part -> new ro.alacrity.kina.distributor.StockUpdate(part.stock(), part.prices()))
                            .orElse(new ro.alacrity.kina.distributor.StockUpdate(0, List.of())));
                }
            }
            return out;
        });
    }

    /**
     * Mouser's {@code startingRecord} is 1-based: on the live API {@code startingRecord=3, records=2} returned the
     * 3rd and 4th result, and both 0 and 1 return the first one.
     */
    static int startingRecord(int offset) {
        return offset + 1;
    }

    private int pageLimit() {
        int configured = config.maxResultsPerSearch();
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

    /** The Mouser part number, null when blank or {@code N/A}. */
    private static String mouserNumber(String s) {
        String v = blankToNull(s);
        return v == null || v.equalsIgnoreCase("N/A") ? null : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
