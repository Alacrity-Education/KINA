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
        return Optional.of(build(source, fetchedAt, stock));
    }

    /**
     * The part as listed, with stock 0 when it has no ships-now stock: only for a part number the user requested
     * explicitly (DESIGN.md 2, stock rule). Empty without a Mouser part number (blank or {@code N/A}: a catalogue part
     * Mouser does not sell).
     */
    public Optional<Part> mapListed(MouserPart source, Instant fetchedAt) {
        if (source == null || isBlank(source.mouserPartNumber())
                || source.mouserPartNumber().strip().equalsIgnoreCase("N/A")) {
            return Optional.empty();
        }
        Integer stock = parseCount(source.availabilityInStock());
        return Optional.of(build(source, fetchedAt, stock == null ? 0 : Math.max(0, stock)));
    }

    private Part build(MouserPart source, Instant fetchedAt, int stock) {
        Map<String, String> attributes = attributes(source);
        return Part.builder()
                .distributor(Distributor.MOUSER)
                .distributorPartNumber(source.mouserPartNumber().trim())
                .manufacturer(blankToNull(source.manufacturer()))
                .manufacturerPartNumber(blankToNull(source.manufacturerPartNumber()))
                .description(blankToNull(source.description()))
                .category(blankToNull(source.category()))
                .packageName(packageName(attributes))
                .stock(stock)
                .minimumOrderQuantity(parseCount(source.min()))
                .orderMultiple(parseCount(source.mult()))
                .prices(prices(source))
                .datasheetUrl(blankToNull(source.dataSheetUrl()))
                .photoUrl(blankToNull(source.imagePath()))
                .productUrl(blankToNull(source.productDetailUrl()))
                .attributes(attributes)
                .extra(extra(source))
                .fetchedAt(fetchedAt)
                .build();
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
