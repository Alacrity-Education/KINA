package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ro.alacrity.kina.TestWiring;
import ro.alacrity.kina.cache.PartCacheRepository;
import ro.alacrity.kina.cache.PhraseJournalRepository;
import ro.alacrity.kina.cache.SearchCacheRepository;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.distributor.DistributorRegistry;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.search.field.PartIndexRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test data builders for the search package. */
@UtilityClass
class RankingFixtures {

    static final Instant FETCHED = Instant.parse("2026-10-05T00:00:00Z");

    /** Insertion-ordered map from alternating keys and values. */
    static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    static Part part(Distributor distributor, String partNumber, String manufacturer, String mpn, String description,
                     String category, String packageName, int stock, String unitPrice, Map<String, String> attributes,
                     Map<String, Object> extra) {
        List<PriceBreak> prices = unitPrice == null ? List.of()
                : List.of(new PriceBreak(1, new BigDecimal(unitPrice), "EUR"));
        return new Part(distributor, partNumber, manufacturer, mpn, description, category, packageName, stock, 1, 1,
                prices, null, null, "https://example.invalid/" + partNumber, attributes, extra, FETCHED);
    }

    static Part part(String mpn, String description, String category, String packageName,
                     Map<String, String> attributes) {
        return part(Distributor.MOUSER, "M-" + mpn, "ACME", mpn, description, category, packageName, 1000, "0.10",
                attributes, Map.of());
    }

    static Part mouser(String mpn, String manufacturer, String description, String category, String packageName,
                       Map<String, String> attributes) {
        return part(Distributor.MOUSER, "M-" + mpn, manufacturer, mpn, description, category, packageName, 1000,
                "0.10", attributes, Map.of());
    }

    static Part tme(String symbol, String manufacturer, String description, String category, String packageName,
                    Map<String, String> attributes) {
        return part(Distributor.TME, symbol, manufacturer, symbol, description, category, packageName, 1000, "0.10",
                attributes, Map.of());
    }

    static Part lcsc(String code, String manufacturer, String mpn, String description, String category,
                     String packageName, Map<String, String> attributes) {
        return part(Distributor.LCSC, code, manufacturer, mpn, description, category, packageName, 1000, "0.01",
                attributes, Map.of("library_type", "Basic"));
    }

    /** Simple part with a given distributor, number, stock and price (for ordering tests). */
    static Part simple(Distributor distributor, String number, String description, int stock, String price) {
        return part(distributor, number, "ACME", number, description, null, null, stock, price, Map.of(), Map.of());
    }

    /** Binds {@link KinaProperties} from {@code kina.*} key/value pairs on top of the record defaults. */
    static KinaProperties properties(String... kv) {
        Map<String, String> source = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            source.put(kv[i], kv[i + 1]);
        }
        source.putIfAbsent("kina.public-base-url", "");
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("kina", Bindable.of(KinaProperties.class));
    }

    /** {@link PartSearchService} and its stages, wired with {@link TestWiring} as Spring would. */
    static PartSearchService searchService(KinaProperties props, DistributorRegistry registry, QueryParser parser,
                                           ParametricExtractor extractor, RankingService ranking,
                                           PartCacheRepository partCache, SearchCacheRepository searchCache,
                                           Clock clock) {
        return searchService(props, registry, parser, extractor, ranking, partCache, searchCache, clock,
                KinaMetrics.NOOP);
    }

    /** The field index and the phrase journal of a test service. */
    record FieldBeans(PartIndexRepository index, PhraseJournalRepository journal) {
    }

    /** As above, counting into {@code metrics}. */
    static PartSearchService searchService(KinaProperties props, DistributorRegistry registry, QueryParser parser,
                                           ParametricExtractor extractor, RankingService ranking,
                                           PartCacheRepository partCache, SearchCacheRepository searchCache,
                                           Clock clock, KinaMetrics metrics) {
        return searchService(props, registry, parser, extractor, ranking, partCache, searchCache, clock, metrics,
                null);
    }

    /** As above with the field index and the phrase journal ({@code field} null: the cached-search path only). */
    static PartSearchService searchService(KinaProperties props, DistributorRegistry registry, QueryParser parser,
                                           ParametricExtractor extractor, RankingService ranking,
                                           PartCacheRepository partCache, SearchCacheRepository searchCache,
                                           Clock clock, KinaMetrics metrics, FieldBeans field) {
        PageCollector pages = TestWiring.wire(new PageCollector(), "extractor", extractor, "clock", clock,
                "metrics", metrics);
        StockRefresher stock = TestWiring.wire(new StockRefresher(), "properties", props, "registry", registry,
                "partCache", partCache, "clock", clock, "metrics", metrics);
        RequestedLookup requested = TestWiring.wire(new RequestedLookup(), "extractor", extractor,
                "partCache", partCache, "clock", clock);
        LcscRetriever lcsc = TestWiring.wire(new LcscRetriever(), "properties", props, "pages", pages,
                "ranking", ranking, "requested", requested);
        CachedDistributorRetriever cached = TestWiring.wire(new CachedDistributorRetriever(), "properties", props,
                "pages", pages, "ranking", ranking, "extractor", extractor, "partCache", partCache,
                "searchCache", searchCache, "clock", clock, "requested", requested, "metrics", metrics);
        if (field != null) {
            FieldFirstSearch fieldFirst = TestWiring.wire(new FieldFirstSearch(), "properties", props,
                    "pages", pages, "ranking", ranking, "extractor", extractor, "partCache", partCache,
                    "searchCache", searchCache, "journal", field.journal(), "index", field.index(), "clock", clock,
                    "metrics", metrics);
            TestWiring.wire(cached, "fieldFirst", fieldFirst, "index", field.index(), "journal", field.journal());
        }
        ParallelRetrieval retrieval = TestWiring.wire(new ParallelRetrieval(), "properties", props,
                "registry", registry, "parser", parser, "lcscRetriever", lcsc, "cachedRetriever", cached);
        ResponseAssembler assembler = TestWiring.wire(new ResponseAssembler(), "properties", props,
                "extractor", extractor, "ranking", ranking, "staleness", stock, "clock", clock);
        return TestWiring.wire(new PartSearchService(), "properties", props, "registry", registry, "parser", parser,
                "ranking", ranking, "retrieval", retrieval, "stockRefresher", stock, "assembler", assembler,
                "metrics", metrics);
    }
}
