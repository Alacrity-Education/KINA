package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import ro.alacrity.kina.cache.CacheStatus;

/**
 * Result of a single part lookup ({@code get_part}). {@code found} is false (and {@code part} null) when the
 * distributor does not know the part or has no ships-now stock for it, or when the lookup failed ({@code error}
 * then carries the distributor error code).
 *
 * @param found       whether {@code part} is present
 * @param distributor the distributor asked
 * @param partNumber  the requested distributor part number
 * @param cache       {@code hit} (served from the Postgres cache), {@code miss}, {@code bypassed}, or
 *                    {@code not_applicable} (LCSC)
 * @param error       null, or "rate_limited", "unavailable", "not_configured", "timeout", "bad_response"
 * @param part        the part (prices trimmed to the 3 smallest brackets), null when not found
 */
@JsonPropertyOrder({"found", "distributor", "part_number", "cache", "error", "part"})
public record PartLookupResponse(
        @JsonProperty("found") boolean found,
        @JsonProperty("distributor") Distributor distributor,
        @JsonProperty("part_number") String partNumber,
        @JsonProperty("cache") CacheStatus cache,
        @JsonProperty("error") String error,
        @JsonProperty("part") PartResponse part
) {

    public static PartLookupResponse found(Distributor distributor, String partNumber, CacheStatus cache,
                                           PartResponse part) {
        return new PartLookupResponse(true, distributor, partNumber, cache, null, part);
    }

    public static PartLookupResponse notFound(Distributor distributor, String partNumber, CacheStatus cache,
                                              String error) {
        return new PartLookupResponse(false, distributor, partNumber, cache, error, null);
    }
}
