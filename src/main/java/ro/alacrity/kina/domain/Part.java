package ro.alacrity.kina.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single offer from one distributor, in stock: {@code stock > 0}. The one exception (DESIGN.md 2, stock rule) is a
 * part requested explicitly by its part number (a part number in a search query, or {@code get_part}) that the
 * distributor lists without ships-now stock: it is built with {@code stock == 0} (never negative), only by the
 * distributor lookup, and is never served to a keyword or parametric search.
 *
 * <p>This record is also the JSON payload stored in {@code cached_parts.payload}; keep it
 * serialisable with the default (camelCase) Jackson mapping.
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
        Instant fetchedAt
) {

    public Part {
        prices = prices == null ? List.of() : List.copyOf(prices);
        attributes = attributes == null ? Map.of() : attributes;
        extra = extra == null ? Map.of() : extra;
    }

    /** The cross-distributor key {@code distributor + ":" + distributorPartNumber}. */
    public String key() {
        return PartKey.of(distributor, distributorPartNumber);
    }
}
