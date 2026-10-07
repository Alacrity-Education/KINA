package ro.alacrity.kina.distributor.lcsc;

import lombok.experimental.UtilityClass;
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
@UtilityClass
public class LcscPartMapper {

    static final String PRODUCT_URL = "https://www.lcsc.com/product-detail/%s.html";
    static final String JLCPCB_URL = "https://jlcpcb.com/partdetail/%s";

    /** Empty when the row has no LCSC number or no ships-now stock. */
    public static Optional<Part> map(JlcpcbRow row, Instant fetchedAt) {
        int stock = row.stockQuantity();
        return stock <= 0 ? Optional.empty() : build(row, fetchedAt, stock);
    }

    /**
     * The part of a row without ships-now stock (stock 0): only for a part number the user requested explicitly
     * (DESIGN.md 2, stock rule). Empty when the row has no LCSC number.
     */
    public static Optional<Part> mapListed(JlcpcbRow row, Instant fetchedAt) {
        return build(row, fetchedAt, Math.max(0, row.stockQuantity()));
    }

    private static Optional<Part> build(JlcpcbRow row, Instant fetchedAt, int stock) {
        String lcsc = blankToNull(row.lcscPart());
        if (lcsc == null) {
            return Optional.empty();
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("library_type", blankToNull(row.libraryType()));
        extra.put("solder_joints", solderJoints(row.solderJoint()));
        extra.put("second_category", blankToNull(row.secondCategory()));
        extra.put("jlcpcb_url", JLCPCB_URL.formatted(lcsc));
        return Optional.of(Part.builder()
                .distributor(Distributor.LCSC)
                .distributorPartNumber(lcsc)
                .manufacturer(blankToNull(row.manufacturer()))
                .manufacturerPartNumber(blankToNull(row.mfrPart()))
                .description(blankToNull(row.description()))
                .category(category(row.firstCategory(), row.secondCategory()))
                .packageName(blankToNull(row.packageName()))
                .stock(stock)
                .prices(JlcpcbPriceParser.parse(row.price()))
                .datasheetUrl(blankToNull(row.datasheet()))
                .productUrl(PRODUCT_URL.formatted(lcsc))
                .attributes(Map.of())
                .extra(extra)
                .fetchedAt(fetchedAt)
                .build());
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
