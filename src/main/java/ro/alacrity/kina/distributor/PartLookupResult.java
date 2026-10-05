package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Part;

import java.util.Locale;
import java.util.Optional;

/**
 * Outcome of one distributor part lookup ({@link DistributorClient#lookup}): the in-stock part, a part the distributor
 * lists without ships-now stock (only its identity, never a {@link Part}: the stock rule holds), or nothing.
 *
 * @param status   what the distributor said
 * @param part     the part when {@link Status#FOUND}, else null
 * @param identity the listed part's identity when {@link Status#OUT_OF_STOCK}, else null
 */
public record PartLookupResult(Status status, Part part, Identity identity) {

    public enum Status { FOUND, OUT_OF_STOCK, NOT_FOUND }

    /**
     * Basic identity of a listed part (no stock, no prices).
     *
     * @param partNumber   the distributor part number
     * @param manufacturer manufacturer name, may be null
     * @param mpn          manufacturer part number, may be null
     * @param description  distributor description, may be null
     */
    public record Identity(String partNumber, String manufacturer, String mpn, String description) {
    }

    public PartLookupResult {
        if (status == Status.FOUND && (part == null || part.stock() <= 0)) {
            throw new IllegalArgumentException("a found part must have ships-now stock");
        }
    }

    public static PartLookupResult found(Part part) {
        return new PartLookupResult(Status.FOUND, part, null);
    }

    public static PartLookupResult outOfStock(Identity identity) {
        return new PartLookupResult(Status.OUT_OF_STOCK, null, identity);
    }

    public static PartLookupResult notFound() {
        return new PartLookupResult(Status.NOT_FOUND, null, null);
    }

    /** {@link #found} for a present part, else {@link #notFound}. */
    public static PartLookupResult of(Optional<Part> part) {
        return part == null ? notFound() : part.filter(p -> p.stock() > 0).map(PartLookupResult::found).orElseGet(PartLookupResult::notFound);
    }

    public Optional<Part> asOptional() {
        return Optional.ofNullable(part);
    }

    /**
     * Part-number comparison key: upper case, letters and digits only, so {@code ERA6AEB5361V} (TME) equals
     * {@code ERA-6AEB5361V} (Mouser); null for null.
     */
    public static String normalize(String partNumber) {
        if (partNumber == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(partNumber.length());
        partNumber.codePoints().filter(Character::isLetterOrDigit)
                .forEach(cp -> out.appendCodePoint(Character.toUpperCase(cp)));
        return out.toString().toUpperCase(Locale.ROOT);
    }

    /** True when both part numbers are non-blank and equal after {@link #normalize}. */
    public static boolean samePartNumber(String a, String b) {
        String na = normalize(a);
        return na != null && !na.isEmpty() && na.equals(normalize(b));
    }
}
