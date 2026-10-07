package ro.alacrity.kina.distributor;

import ro.alacrity.kina.domain.Part;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Outcome of one distributor part lookup ({@link DistributorClient#lookup}): the in-stock part, a part the distributor
 * lists without ships-now stock (its identity and, when the distributor gives enough data, the listed part with stock
 * 0: the stock rule allows it only for a part number the user requested explicitly, DESIGN.md 2), or nothing.
 *
 * @param status   what the distributor said
 * @param part     the in-stock part when {@link Status#FOUND}; the listed part with stock 0 (or null) when
 *                 {@link Status#OUT_OF_STOCK}; else null
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
        if (status == Status.OUT_OF_STOCK && part != null && part.stock() > 0) {
            throw new IllegalArgumentException("a listed out-of-stock part has stock 0");
        }
    }

    public static PartLookupResult found(Part part) {
        return new PartLookupResult(Status.FOUND, part, null);
    }

    public static PartLookupResult outOfStock(Identity identity) {
        return new PartLookupResult(Status.OUT_OF_STOCK, null, identity);
    }

    /** Listed without ships-now stock, with the listed part (stock 0, prices as listed; may be null). */
    public static PartLookupResult outOfStock(Identity identity, Part listed) {
        return new PartLookupResult(Status.OUT_OF_STOCK, listed, identity);
    }

    public static PartLookupResult notFound() {
        return new PartLookupResult(Status.NOT_FOUND, null, null);
    }

    /** {@link #found} for a present part, else {@link #notFound}. */
    public static PartLookupResult of(Optional<Part> part) {
        return part == null ? notFound() : part.filter(p -> p.stock() > 0).map(PartLookupResult::found).orElseGet(PartLookupResult::notFound);
    }

    /** The in-stock part ({@link Status#FOUND}), else empty. */
    public Optional<Part> asOptional() {
        return status == Status.FOUND ? Optional.ofNullable(part) : Optional.empty();
    }

    /** The part listed without ships-now stock ({@link Status#OUT_OF_STOCK}, stock 0), else empty. */
    public Optional<Part> listed() {
        return status == Status.OUT_OF_STOCK ? Optional.ofNullable(part) : Optional.empty();
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
    /** Characters a part number may keep when sent to a distributor (TME refuses spaces and some others). */
    private static final java.util.regex.Pattern NOT_PART_NUMBER = java.util.regex.Pattern.compile("[^A-Za-z0-9\\-_./+#,()]");

    /**
     * The spellings of a typed part number to try, in order (DESIGN.md 4 {@code get_part}): trimmed, with runs of
     * whitespace as {@code -} ({@code HCMA0703 2R2 R} -&gt; {@code HCMA0703-2R2-R}), then without whitespace; characters
     * a distributor may refuse are dropped. A part number without whitespace has one variant.
     */
    public static List<String> variants(String partNumber) {
        if (partNumber == null || partNumber.isBlank()) {
            return List.of();
        }
        String trimmed = partNumber.strip();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(NOT_PART_NUMBER.matcher(trimmed.replaceAll("\\s+", "-")).replaceAll(""));
        out.add(NOT_PART_NUMBER.matcher(trimmed.replaceAll("\\s+", "")).replaceAll(""));
        out.removeIf(String::isBlank);
        return List.copyOf(out);
    }

    public static boolean samePartNumber(String a, String b) {
        String na = normalize(a);
        return na != null && !na.isEmpty() && na.equals(normalize(b));
    }
}
