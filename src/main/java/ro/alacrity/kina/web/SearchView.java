package ro.alacrity.kina.web;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.DistributorResult;
import ro.alacrity.kina.domain.ParsedQueryResponse;
import ro.alacrity.kina.domain.ParsedQueryResponse.ConnectorResponse;
import ro.alacrity.kina.domain.PartResponse;
import ro.alacrity.kina.domain.PriceResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * View models of the Search tab, built from a {@link ro.alacrity.kina.domain.SearchResponse}. Every value stays text
 * for {@code th:text} (escaped); URLs from distributors are kept only when they are http(s), so a {@code javascript:}
 * link can never be rendered.
 */
@UtilityClass
public class SearchView {

    /** One labelled value of the parsed summary. */
    public record Item(String label, String value) {
    }

    /** One distributor card and its parts table; {@code images}: some row has a photo (the thumbnail column). */
    public record Section(DistributorResult result, List<Item> facts, List<PartRow> rows, boolean images) {
    }

    /**
     * One row of the parts table. {@code imageUrl} is the distributor's product photo (null for LCSC and whenever it is
     * not an http(s) URL); the page links it straight from the distributor, KINA never downloads or stores it.
     */
    public record PartRow(PartResponse part, String match, String productUrl, String datasheetUrl, String imageUrl,
                          List<String> prices, String order, List<Item> attributes, List<Item> details) {
    }

    static List<Item> parsedItems(ParsedQueryResponse parsed) {
        List<Item> items = new ArrayList<>();
        if (parsed == null) {
            return items;
        }
        add(items, "family", parsed.family());
        if (parsed.constraints() != null) {
            parsed.constraints().forEach((k, v) -> add(items, k, v));
        }
        add(items, "dielectric", parsed.dielectric());
        add(items, "package", parsed.packageName());
        add(items, "mounting", parsed.mounting());
        add(items, "technology", parsed.technology());
        add(items, "elements", parsed.elements());
        add(items, "polarity", parsed.polarity());
        add(items, "subtype", parsed.subtype());
        add(items, "form factor", parsed.formFactor());
        add(items, "part numbers", join(parsed.partNumbers()));
        add(items, "keywords", join(parsed.keywords()));
        ConnectorResponse c = parsed.connector();
        if (c != null) {
            add(items, "connector type", c.type());
            add(items, "series", c.series());
            add(items, "gender", c.gender());
            add(items, "positions", c.positions());
            add(items, "rows", c.rows());
            add(items, "pitch", c.pitch());
            add(items, "orientation", c.orientation());
            add(items, "USB type", c.usbType());
            add(items, "USB standard", c.usbStandard());
            add(items, "USB speed (Gbit/s)", c.usbSpeedGbps());
            add(items, "pin configuration", c.pinConfiguration());
            add(items, "mounting style", c.mountingStyle());
            add(items, "features", join(c.features()));
        }
        return items;
    }

    static Section section(DistributorResult r) {
        List<Item> facts = new ArrayList<>();
        add(facts, "distributor query", r.distributorQuery());
        add(facts, "fallback query", r.fallbackQuery());
        add(facts, "requested part found", r.requestedPartFound());
        add(facts, "constraints relaxed", join(r.constraintsRelaxed()));
        add(facts, "query terms dropped", join(r.queryTermsDropped()));
        if (r.rateLimitWaitedMs() > 0) {
            add(facts, "rate-limit wait", r.rateLimitWaitedMs() + " ms");
        }
        List<PartRow> rows = r.parts().stream().map(SearchView::row).toList();
        return new Section(r, facts, rows, rows.stream().anyMatch(row -> row.imageUrl() != null));
    }

    static PartRow row(PartResponse p) {
        List<String> prices = p.prices() == null ? List.of() : p.prices().stream().map(SearchView::price).toList();
        String order = p.totalPrice() == null ? null : p.orderedQuantity() + " pcs: " + p.totalPrice().toPlainString()
                + currency(p.prices());
        List<Item> attributes = new ArrayList<>();
        if (p.attributes() != null) {
            p.attributes().forEach((k, v) -> add(attributes, k, v));
        }
        List<Item> details = new ArrayList<>();
        add(details, "category", p.category());
        add(details, "package", p.packageName());
        add(details, "manufacturer id", p.manufacturerId());
        add(details, "min order", p.minOrderQty());
        add(details, "order multiple", p.orderMultiple());
        add(details, "stock as of", p.stockAsOf());
        add(details, "availability", p.availability() == null ? null : p.availability().note());
        add(details, "mismatches", join(p.mismatches()));
        add(details, "unverified", join(p.unverified()));
        if (p.score() != null) {
            add(details, "score", String.format(Locale.ROOT, "%.2f", p.score()));
        }
        if (p.extra() != null) {
            Map<String, Object> extra = new LinkedHashMap<>(p.extra());
            extra.forEach((k, v) -> add(details, k, v));
        }
        return new PartRow(p, p.match() == null ? null : String.format(Locale.ROOT, "%.2f", p.match()),
                safeUrl(p.productUrl()), safeUrl(p.datasheetUrl()), imageUrl(p.photoUrl()), prices, order, attributes, details);
    }

    static String price(PriceResponse price) {
        return price.qty() + "+: " + (price.unitPrice() == null ? "?" : price.unitPrice().toPlainString())
                + (price.currency() == null ? "" : " " + price.currency());
    }

    /** The URL when it is an absolute http(s) URL, else null. */
    static String safeUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        String lower = u.toLowerCase(Locale.ROOT);
        return lower.startsWith("https://") || lower.startsWith("http://") ? u : null;
    }

    /** As {@link #safeUrl(String)}, with a protocol-relative URL ({@code //host/...}) read as https. */
    static String imageUrl(String url) {
        if (url == null) {
            return null;
        }
        String u = url.strip();
        return safeUrl(u.startsWith("//") ? "https:" + u : u);
    }

    private static String currency(List<PriceResponse> prices) {
        return prices == null ? "" : prices.stream().map(PriceResponse::currency).filter(Objects::nonNull).findFirst()
                .map(c -> " " + c).orElse("");
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
    }

    private static void add(List<Item> items, String label, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (!text.isBlank()) {
            items.add(new Item(label, text));
        }
    }
}
