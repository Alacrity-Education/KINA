package ro.alacrity.kina.distributor.lcsc;

import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a {@link JlcpcbRow} to a {@link Part} (DESIGN.md 9.3). {@code attributes} stay empty: the search layer
 * enriches them from the description with the shared parametric extractor.
 */
public final class LcscPartMapper {

    static final String PRODUCT_URL = "https://www.lcsc.com/product-detail/%s.html";
    static final String JLCPCB_URL = "https://jlcpcb.com/partdetail/%s";

    private LcscPartMapper() {
    }

    /** Empty when the row has no LCSC number or no ships-now stock. */
    public static Optional<Part> map(JlcpcbRow row, Instant fetchedAt) {
        String lcsc = blankToNull(row.lcscPart());
        int stock = row.stockQuantity();
        if (lcsc == null || stock <= 0) {
            return Optional.empty();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("library_type", blankToNull(row.libraryType()));
        extra.put("solder_joints", solderJoints(row.solderJoint()));
        extra.put("second_category", blankToNull(row.secondCategory()));
        extra.put("jlcpcb_url", JLCPCB_URL.formatted(lcsc));
        return Optional.of(new Part(
                Distributor.LCSC,
                lcsc,
                blankToNull(row.manufacturer()),
                blankToNull(row.mfrPart()),
                blankToNull(row.description()),
                category(row.firstCategory(), row.secondCategory()),
                blankToNull(row.packageName()),
                stock,
                null,
                null,
                JlcpcbPriceParser.parse(row.price()),
                blankToNull(row.datasheet()),
                null,
                PRODUCT_URL.formatted(lcsc),
                Map.of(),
                extra,
                fetchedAt));
    }

    static String category(String first, String second) {
        String a = blankToNull(first);
        String b = blankToNull(second);
        if (a == null) {
            return b;
        }
        return b == null ? a : a + " / " + b;
    }

    private static Object solderJoints(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
