package ro.alacrity.kina.distributor.mouser;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PriceBreak;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps a {@link MouserPart} to the domain {@link Part} (DESIGN.md 9.1). Stateless and thread-safe. */
public final class MouserPartMapper {

    /** Attribute names that carry the package, checked in this order (case-insensitive). */
    static final List<String> PACKAGE_ATTRIBUTES = List.of("Package / Case", "Case Code - in");

    /** @return the part, or empty when it has no ships-now stock ({@code AvailabilityInStock} &lt;= 0 or unparsable) */
    public Optional<Part> map(MouserPart source, Instant fetchedAt) {
        if (source == null || isBlank(source.mouserPartNumber())) {
            return Optional.empty();
        }
        Integer stock = parseCount(source.availabilityInStock());
        if (stock == null || stock <= 0) {
            return Optional.empty();
        }
        Map<String, String> attributes = attributes(source);
        return Optional.of(new Part(
                Distributor.MOUSER,
                source.mouserPartNumber().trim(),
                blankToNull(source.manufacturer()),
                blankToNull(source.manufacturerPartNumber()),
                blankToNull(source.description()),
                blankToNull(source.category()),
                packageName(attributes),
                stock,
                parseCount(source.min()),
                parseCount(source.mult()),
                prices(source),
                blankToNull(source.dataSheetUrl()),
                blankToNull(source.imagePath()),
                blankToNull(source.productDetailUrl()),
                attributes,
                extra(source),
                fetchedAt));
    }

    /** {@code ProductAttributes} by name, insertion-ordered; repeated names are joined with {@code ", "}. */
    static Map<String, String> attributes(MouserPart source) {
        Map<String, String> attributes = new LinkedHashMap<>();
        for (MouserAttribute attribute : source.productAttributes()) {
            if (attribute == null || isBlank(attribute.attributeName()) || isBlank(attribute.attributeValue())) {
                continue;
            }
            String name = attribute.attributeName().trim();
            String value = attribute.attributeValue().trim();
            attributes.merge(name, value, (a, b) -> a + ", " + b);
        }
        return attributes;
    }

    static String packageName(Map<String, String> attributes) {
        for (String wanted : PACKAGE_ATTRIBUTES) {
            for (Map.Entry<String, String> entry : attributes.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(wanted)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** Complete price list ascending by quantity; unparsable brackets are skipped. */
    static List<PriceBreak> prices(MouserPart source) {
        List<PriceBreak> prices = new ArrayList<>();
        for (MouserPriceBreak priceBreak : source.priceBreaks()) {
            if (priceBreak == null || priceBreak.quantity() == null || priceBreak.quantity() <= 0) {
                continue;
            }
            Optional<BigDecimal> price = MouserPriceParser.parse(priceBreak.price());
            price.ifPresent(p -> prices.add(new PriceBreak(priceBreak.quantity(), p, blankToNull(priceBreak.currency()))));
        }
        prices.sort(Comparator.comparingInt(PriceBreak::quantity));
        return prices;
    }

    /** Extra keys are always present (value may be null) so cached payloads have a stable shape. */
    static Map<String, Object> extra(MouserPart source) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("lifecycle_status", blankToNull(source.lifecycleStatus()));
        extra.put("rohs", blankToNull(source.rohsStatus()));
        extra.put("lead_time", blankToNull(source.leadTime()));
        extra.put("factory_stock", parseCount(source.factoryStock()));
        extra.put("category", blankToNull(source.category()));
        extra.put("suggested_replacement", blankToNull(source.suggestedReplacement()));
        extra.put("reeling", source.reeling());
        extra.put("sales_maximum_order_qty", parseCount(source.salesMaximumOrderQty()));
        List<Map<String, Object>> onOrder = new ArrayList<>();
        for (MouserOnOrder order : source.availabilityOnOrder()) {
            if (order != null && order.quantity() != null) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("quantity", order.quantity());
                entry.put("date", blankToNull(order.date()));
                onOrder.add(entry);
            }
        }
        extra.put("availability_on_order", onOrder);
        return extra;
    }

    /** Digits only ("76689", "76.689", "76689 In Stock" -&gt; 76689); null when there are none. Clamped to int range. */
    static Integer parseCount(String raw) {
        if (raw == null || raw.strip().startsWith("-")) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return null;
        }
        if (digits.length() > 10) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.min(Long.parseLong(digits), Integer.MAX_VALUE);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
