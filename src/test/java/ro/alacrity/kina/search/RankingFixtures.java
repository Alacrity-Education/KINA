package ro.alacrity.kina.search;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test data builders for the search package. */
final class RankingFixtures {

    static final Instant FETCHED = Instant.parse("2026-10-05T00:00:00Z");

    private RankingFixtures() {
    }

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
}
