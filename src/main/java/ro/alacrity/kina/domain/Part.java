package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Builder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A single offer from one distributor, in stock: {@code stock > 0}. The one exception (DESIGN.md 2, stock rule) is a
 * part requested explicitly by its part number (a part number in a search query, or {@code get_part}) that the
 * distributor lists without ships-now stock: it is built with {@code stock == 0} (never negative), only by the
 * distributor lookup, and is never served to a keyword or parametric search.
 *
 * <p>This record is also the JSON payload stored in {@code cached_parts.payload}; keep it
 * serialisable with the default (camelCase) Jackson mapping. The payload holds the distributor's attributes only
 * ({@link #asStored()}): the derived ones are added again by every read (DESIGN.md 3.2 "Cache model").
 *
 * @param distributor            the distributor offering the part
 * @param distributorPartNumber  LCSC "Cxxxxx", TME symbol, Mouser part number
 * @param manufacturer           manufacturer name
 * @param manufacturerPartNumber manufacturer part number (MPN)
 * @param description            distributor description
 * @param category               distributor category path, may be null
 * @param packageName            "0805", "SOT-23", may be null
 * @param stock                  quantity that ships now; &gt; 0, or 0 for an explicitly requested part listed without
 *                               stock
 * @param minimumOrderQuantity   null when unknown
 * @param orderMultiple          null when unknown
 * @param prices                 ascending by quantity, complete list as fetched
 * @param datasheetUrl           may be null
 * @param photoUrl               null when the distributor gives none (LCSC)
 * @param productUrl             distributor product page
 * @param attributes             parametric attributes, insertion-ordered, e.g. "Capacitance" -&gt; "10uF"
 * @param extra                  distributor specific details (lifecycle, RoHS, library type, lead time...)
 * @param fetchedAt              when the data was fetched from the distributor
 * @param derivedAttributes      the keys of {@code attributes} that {@code ParametricExtractor.enrich} added (not the
 *                               distributor's); never stored
 */
@Builder(toBuilder = true)
public record Part(
        Distributor distributor,
        String distributorPartNumber,
        String manufacturer,
        String manufacturerPartNumber,
        String description,
        String category,
        String packageName,
        int stock,
        Integer minimumOrderQuantity,
        Integer orderMultiple,
        List<PriceBreak> prices,
        String datasheetUrl,
        String photoUrl,
        String productUrl,
        Map<String, String> attributes,
        Map<String, Object> extra,
        Instant fetchedAt,
        @JsonIgnore Set<String> derivedAttributes
) {

    public Part {
        prices = prices == null ? List.of() : List.copyOf(prices);
        attributes = attributes == null ? Map.of() : attributes;
        extra = extra == null ? Map.of() : extra;
        derivedAttributes = derivedAttributes == null ? Set.of() : Set.copyOf(derivedAttributes);
    }

    /** A part as a distributor delivers it, without derived attributes. */
    public Part(Distributor distributor, String distributorPartNumber, String manufacturer,
                String manufacturerPartNumber, String description, String category, String packageName, int stock,
                Integer minimumOrderQuantity, Integer orderMultiple, List<PriceBreak> prices, String datasheetUrl,
                String photoUrl, String productUrl, Map<String, String> attributes, Map<String, Object> extra,
                Instant fetchedAt) {
        this(distributor, distributorPartNumber, manufacturer, manufacturerPartNumber, description, category,
                packageName, stock, minimumOrderQuantity, orderMultiple, prices, datasheetUrl, photoUrl, productUrl,
                attributes, extra, fetchedAt, Set.of());
    }

    /** The part as {@code cached_parts} stores it: the distributor's attributes only. */
    public Part asStored() {
        if (derivedAttributes.isEmpty()) {
            return this;
        }
        Map<String, String> own = new LinkedHashMap<>(attributes);
        own.keySet().removeAll(derivedAttributes);
        return toBuilder().attributes(own).derivedAttributes(Set.of()).build();
    }

    /** The cross-distributor key {@code distributor + ":" + distributorPartNumber}. */
    public String key() {
        return PartKey.of(distributor, distributorPartNumber);
    }
}
