package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import ro.alacrity.kina.cache.CacheStatus;

/**
 * Result of a single part lookup ({@code get_part}). {@code found} is false (and {@code part} null) when the
 * distributor does not know the part ({@code reason} {@code not_found}), lists it without ships-now stock
 * ({@code reason} {@code out_of_stock}, with the listed part's {@code identity}), or when the lookup failed
 * ({@code error} then carries the distributor error code and {@code reason} is null).
 *
 * @param found       whether {@code part} is present
 * @param distributor the distributor asked
 * @param partNumber  the requested part number
 * @param cache       {@code hit} (served from the Postgres cache), {@code miss}, {@code bypassed}, or
 *                    {@code not_applicable} (LCSC)
 * @param error       null, or "rate_limited", "unavailable", "not_configured", "timeout", "bad_response"
 * @param reason      null when found or failed, else {@value #OUT_OF_STOCK} or {@value #NOT_FOUND}
 * @param identity    for {@value #OUT_OF_STOCK}: the listed part (distributor part number, manufacturer, mpn,
 *                    description; no stock, no prices), else null (omitted)
 * @param part        the part (prices trimmed to the 3 smallest brackets), null when not found
 */
@JsonPropertyOrder({"found", "distributor", "part_number", "cache", "error", "reason", "identity", "part"})
public record PartLookupResponse(
        @JsonProperty("found") boolean found,
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("part_number") String partNumber,
        @JsonProperty("cache") CacheStatus cache,
        @JsonProperty("error") String error,
        @JsonProperty("reason") String reason,
        @JsonProperty("identity") @JsonInclude(JsonInclude.Include.NON_NULL) Identity identity,
        @JsonProperty("part") PartResponse part
) {

    public static final String OUT_OF_STOCK = "out_of_stock";
    public static final String NOT_FOUND = "not_found";

    /**
     * Basic identity of a part the distributor lists without ships-now stock.
     *
     * @param partNumber   the distributor part number (may differ from the requested one, e.g. a Mouser number found by
     *                     its MPN); null for a catalogue part Mouser does not sell (its part number is "N/A")
     * @param manufacturer manufacturer, may be null
     * @param mpn          manufacturer part number, may be null
     * @param description  distributor description, may be null
     */
    @JsonPropertyOrder({"part_number", "manufacturer", "mpn", "description"})
    public record Identity(
            @JsonProperty("part_number") String partNumber,
            @JsonProperty("manufacturer") String manufacturer,
            @JsonProperty("mpn") String mpn,
            @JsonProperty("description") String description) {
    }

    public static PartLookupResponse found(Distributor distributor, String partNumber, CacheStatus cache,
                                           PartResponse part) {
        return new PartLookupResponse(true, distributor, partNumber, cache, null, null, null, part);
    }

    /** A failed lookup ({@code error} set) or, with a null error, an unknown part ({@value #NOT_FOUND}). */
    public static PartLookupResponse notFound(Distributor distributor, String partNumber, CacheStatus cache,
                                              String error) {
        return new PartLookupResponse(false, distributor, partNumber, cache, error, error == null ? NOT_FOUND : null,
                null, null);
    }

    /** The distributor lists the part but has no ships-now stock for it. */
    public static PartLookupResponse outOfStock(Distributor distributor, String partNumber, CacheStatus cache,
                                                Identity identity) {
        return new PartLookupResponse(false, distributor, partNumber, cache, null, OUT_OF_STOCK, identity, null);
    }
}
