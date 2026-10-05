package ro.alacrity.kina.distributor.tme;

import org.springframework.web.util.UriUtils;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Maps TME API v2 product, data, parameter and file records to {@link Part} (DESIGN.md section 9.2). */
final class TmePartMapper {

    /** Parameter names that carry the package, in order of preference. */
    static final List<String> PACKAGE_PARAMETERS = List.of("Case - inch", "Case", "Package", "Case - mm");

    /** Document type of datasheets ("DTE - Documentation" in the OpenAPI document). */
    static final String DATASHEET_TYPE = "DTE";

    private TmePartMapper() {
    }

    /**
     * Builds the part; empty when there is no stock record or {@code stock_quantity <= 0} (stock rule).
     *
     * @param product      element of /products or /products/search
     * @param data         element of /products/data for the same symbol (null when missing)
     * @param parameters   element of /products/parameters (null when missing)
     * @param datasheetUrl absolute datasheet URL or null
     */
    static Optional<Part> toPart(TmeResponses.Product product, TmeResponses.ProductData data,
                                 TmeResponses.ProductParameters parameters, String datasheetUrl, Instant fetchedAt) {
        return toPart(product, data, parameters, datasheetUrl, fetchedAt, List.of());
    }

    /**
     * As {@link #toPart(TmeResponses.Product, TmeResponses.ProductData, TmeResponses.ProductParameters, String, Instant)},
     * additionally empty when the product carries one of the {@code excludedStatuses} (compared case-insensitively):
     * statuses such as {@code CANNOT_BE_ORDERED} mean the stock does not ship now.
     */
    static Optional<Part> toPart(TmeResponses.Product product, TmeResponses.ProductData data,
                                 TmeResponses.ProductParameters parameters, String datasheetUrl, Instant fetchedAt,
                                 Collection<String> excludedStatuses) {
        if (product == null || product.symbol() == null || data == null || data.stockQuantity() == null) {
            return Optional.empty();
        }
        if (hasExcludedStatus(product, excludedStatuses)) {
            return Optional.empty();
        }
        int stock = toIntFloor(data.stockQuantity());
        if (stock <= 0) {
            return Optional.empty();
        }
        String symbol = product.symbol();
        List<TmeResponses.Parameter> params = parameters == null || parameters.parameters() == null
                || parameters.parameters().elements() == null ? List.of() : parameters.parameters().elements();

        TmeResponses.Prices prices = data.prices();
        return Optional.of(Part.builder()
                .distributor(Distributor.TME)
                .distributorPartNumber(symbol)
                .manufacturer(product.manufacturer() == null ? null : product.manufacturer().name())
                .manufacturerPartNumber(first(product.manufacturerSymbols()))
                .description(product.description())
                .category(product.category() == null ? null : product.category().name())
                .packageName(packageName(params))
                .stock(stock)
                .minimumOrderQuantity(toIntCeil(product.minimalAmount()))
                .orderMultiple(toIntCeil(product.multiples()))
                .prices(priceBreaks(prices))
                .datasheetUrl(datasheetUrl)
                .photoUrl(absoluteUrl(product.assets() == null || product.assets().primaryPhoto() == null
                        ? null : product.assets().primaryPhoto().prime()))
                .productUrl(productUrl(symbol))
                .attributes(attributes(params))
                .extra(extra(product, data))
                .fetchedAt(fetchedAt)
                .build());
    }

    /** True when one of the product's {@code product_status} values is in {@code excludedStatuses} (ignoring case). */
    static boolean hasExcludedStatus(TmeResponses.Product product, Collection<String> excludedStatuses) {
        if (product == null || product.productStatus() == null || excludedStatuses == null
                || excludedStatuses.isEmpty()) {
            return false;
        }
        for (String status : product.productStatus()) {
            if (status == null) {
                continue;
            }
            for (String excluded : excludedStatuses) {
                if (excluded != null && excluded.strip().equalsIgnoreCase(status.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    static String productUrl(String symbol) {
        return "https://www.tme.eu/en/details/" + UriUtils.encodePathSegment(symbol, StandardCharsets.UTF_8) + "/";
    }

    /** First value of the first non-empty parameter among {@link #PACKAGE_PARAMETERS}. */
    static String packageName(List<TmeResponses.Parameter> params) {
        for (String name : PACKAGE_PARAMETERS) {
            for (TmeResponses.Parameter param : params) {
                if (name.equalsIgnoreCase(param.name())) {
                    String value = values(param).stream().findFirst().orElse(null);
                    if (value != null) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    /** Parameter name -> values joined with ", "; parameters without values are skipped; repeated names are merged. */
    static Map<String, String> attributes(List<TmeResponses.Parameter> params) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (TmeResponses.Parameter param : params) {
            List<String> values = values(param);
            if (param.name() == null || param.name().isBlank() || values.isEmpty()) {
                continue;
            }
            attributes.merge(param.name(), String.join(", ", values), (a, b) -> a + ", " + b);
        }
        return attributes;
    }

    private static List<String> values(TmeResponses.Parameter param) {
        if (param.values() == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (TmeResponses.ParameterValue value : param.values()) {
            if (value != null && value.value() != null && !value.value().isBlank()) {
                values.add(value.value().strip());
            }
        }
        return values;
    }

    static List<PriceBreak> priceBreaks(TmeResponses.Prices prices) {
        if (prices == null || prices.elements() == null) {
            return List.of();
        }
        List<PriceBreak> breaks = new ArrayList<>();
        for (TmeResponses.Price price : prices.elements()) {
            Integer quantity = toIntCeil(price.amount());
            if (quantity == null || quantity <= 0 || price.price() == null) {
                continue;
            }
            breaks.add(new PriceBreak(quantity, price.price(), prices.currency()));
        }
        breaks.sort(Comparator.comparingInt(PriceBreak::quantity));
        return breaks;
    }

    private static Map<String, Object> extra(TmeResponses.Product product, TmeResponses.ProductData data) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("product_status", product.productStatus() == null ? List.of() : List.copyOf(product.productStatus()));
        putIfNotNull(extra, "category_id", product.category() == null ? null : product.category().id());
        putIfNotNull(extra, "manufacturer_id", product.manufacturer() == null ? null : product.manufacturer().id());
        TmeResponses.Unit unit = product.unit() != null ? product.unit() : data.unit();
        putIfNotNull(extra, "unit", unit == null ? null : (unit.shortName() != null ? unit.shortName() : unit.id()));
        if (product.packing() != null && product.packing().elements() != null && !product.packing().elements().isEmpty()) {
            List<Map<String, Object>> packing = new ArrayList<>();
            for (TmeResponses.PackingElement element : product.packing().elements()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                putIfNotNull(entry, "id", element.id());
                putIfNotNull(entry, "amount", toIntCeil(element.amount()));
                packing.add(entry);
            }
            extra.put("packing", packing);
        }
        TmeResponses.Prices prices = data.prices();
        if (prices != null) {
            putIfNotNull(extra, "price_type", prices.type());
            putIfNotNull(extra, "tax_rate", prices.tax() == null ? null : prices.tax().rate());
            extra.put("special_prices", prices.elements() != null
                    && prices.elements().stream().anyMatch(p -> Boolean.TRUE.equals(p.special())));
        }
        return extra;
    }

    /**
     * Picks the datasheet among a product's documents: the first {@code DTE} document, preferring a PDF.
     * Returns an absolute {@code https:} URL or null.
     */
    static String datasheetUrl(TmeResponses.ProductFiles files) {
        if (files == null || files.documents() == null || files.documents().elements() == null) {
            return null;
        }
        TmeResponses.Document firstDte = null;
        for (TmeResponses.Document document : files.documents().elements()) {
            if (document == null || document.url() == null || !DATASHEET_TYPE.equalsIgnoreCase(document.type())) {
                continue;
            }
            if (isPdf(document)) {
                return absoluteUrl(document.url());
            }
            if (firstDte == null) {
                firstDte = document;
            }
        }
        return firstDte == null ? null : absoluteUrl(firstDte.url());
    }

    private static boolean isPdf(TmeResponses.Document document) {
        String name = document.fileName() != null ? document.fileName() : document.url();
        return name.toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    /** Prefixes protocol-relative URLs ({@code //host/...}) with {@code https:}; null-safe. */
    static String absoluteUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.startsWith("//") ? "https:" + url : url;
    }

    private static String first(List<String> values) {
        if (values == null) {
            return null;
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

    private static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static int toIntFloor(BigDecimal value) {
        BigDecimal floor = value.setScale(0, RoundingMode.FLOOR);
        return floor.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : floor.intValue();
    }

    private static Integer toIntCeil(BigDecimal value) {
        if (value == null) {
            return null;
        }
        BigDecimal ceil = value.setScale(0, RoundingMode.CEILING);
        return ceil.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : ceil.intValue();
    }
}
