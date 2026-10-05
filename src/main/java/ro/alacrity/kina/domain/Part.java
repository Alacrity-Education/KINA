package ro.alacrity.kina.domain;

import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A single in-stock offer from one distributor. Never construct one with {@code stock <= 0}.
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
 * @param stock                  quantity that ships now; always &gt; 0
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
