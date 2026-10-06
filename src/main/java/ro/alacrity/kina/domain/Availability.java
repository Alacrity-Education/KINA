package ro.alacrity.kina.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalised availability of a part (DESIGN.md 4 and 9), present on every part of a response:
 * {@code {"status": "in_stock", "note": "Ships now from stock."}}. Derived from the stock, the requested quantity and
 * the distributor's flags (TME {@code product_status}, Mouser {@code LifecycleStatus} and
 * {@code SalesMaximumOrderQty}); the raw flags stay in {@code extra} ({@code detail=full}). {@link #lifecycleOf} gives
 * the separate {@code lifecycle} value.
 *
 * <p>TME status meanings (TME API documentation): {@code HARDLY_AVAILABLE} "limited market availability" (a supply
 * flag, not low stock: TME may hold 150k+ pieces), {@code AVAILABLE_WHILE_STOCKS_LAST} "available for sale while stocks
 * last" (no restocking), {@code MOQ_VALID_WHILE_STOCKS_LAST} "the MOQ may change after the product is sold out",
 * {@code NEW}, {@code PROMOTED} (no availability meaning), {@code DANGEROUS} / {@code OVERSIZED} (shipping
 * restrictions), {@code CANNOT_BE_ORDERED} (not for sale in your country), {@code ONLY_FOR_SPECIAL_ORDER},
 * {@code EXTERNAL_WAREHOUSE}; {@code NOT_IN_OFFER}, {@code PRODUCT_BLOCKED}, {@code INVALID} and
 * {@code BLOCKED_FOR_ZBL_*} are excluded from results with the last three statuses before them (not ships-now).
 *
 * <p>The status is the stock situation only; supply and lifecycle flags (TME {@code HARDLY_AVAILABLE}, Mouser end of
 * life) are the separate {@code lifecycle} ({@link #lifecycleOf}) and a note.
 *
 * @param status {@value #IN_STOCK}, {@value #LOW_STOCK} (fewer than {@code kina.search.low-stock-threshold} pieces,
 *               default 10, or fewer than twice the requested quantity), {@value #LIMITED} (fewer pieces ship now
 *               than requested), {@value #LAST_UNITS} (sold while the stock lasts, no restocking: TME
 *               {@code AVAILABLE_WHILE_STOCKS_LAST}, Mouser end of life / obsolete / not recommended for new
 *               designs), {@value #SPECIAL_ORDER} (TME {@code ONLY_FOR_SPECIAL_ORDER}, {@code CANNOT_BE_ORDERED}) or
 *               {@value #EXTERNAL_WAREHOUSE} (TME {@code EXTERNAL_WAREHOUSE}); the last two are excluded from
 *               results by default
 * @param note   one or more plain sentences
 */
@JsonPropertyOrder({"status", "note"})
public record Availability(@JsonProperty("status") String status, @JsonProperty("note") String note) {

    public static final String IN_STOCK = "in_stock";
    public static final String LOW_STOCK = "low_stock";
    public static final String LIMITED = "limited";
    public static final String LAST_UNITS = "last_units";
    /** A {@code lifecycle} value (TME {@code HARDLY_AVAILABLE}); no longer an availability status. */
    public static final String SUPPLY_CONSTRAINED = "supply_constrained";
    public static final String SPECIAL_ORDER = "special_order";
    public static final String EXTERNAL_WAREHOUSE = "external_warehouse";

    /** {@code lifecycle} values ({@link #lifecycleOf}). */
    public static final String ACTIVE = "active";
    public static final String LAST_TIME_BUY = "last_time_buy";
    public static final String NEW = "new";

    /** Status precedence, most severe first. */
    private static final List<String> SEVERITY = List.of(SPECIAL_ORDER, EXTERNAL_WAREHOUSE, LAST_UNITS, LIMITED,
            LOW_STOCK, IN_STOCK);
    /** Default {@code kina.search.low-stock-threshold}. */
    public static final int DEFAULT_LOW_STOCK_THRESHOLD = 10;
    private static final Set<String> MOUSER_END_OF_LIFE = Set.of("end of life", "eol", "obsolete",
            "not recommended for new designs", "nrnd", "last time buy");

    /**
     * The part's lifecycle at its distributor: {@value #LAST_TIME_BUY} (TME {@code AVAILABLE_WHILE_STOCKS_LAST},
     * Mouser end of life / obsolete / NRND), {@value #SUPPLY_CONSTRAINED} (TME {@code HARDLY_AVAILABLE}),
     * {@value #NEW} (TME {@code NEW}, Mouser "New Product") or {@value #ACTIVE}.
     */
    public static String lifecycleOf(Part part) {
        Set<String> tme = statuses(part.extra().get("product_status"));
        String mouser = mouserLifecycle(part);
        if (tme.contains("AVAILABLE_WHILE_STOCKS_LAST")
                || mouser != null && MOUSER_END_OF_LIFE.contains(mouser.toLowerCase(Locale.ROOT))) {
            return LAST_TIME_BUY;
        }
        if (tme.contains("HARDLY_AVAILABLE")) {
            return SUPPLY_CONSTRAINED;
        }
        if (tme.contains("NEW") || mouser != null && mouser.toLowerCase(Locale.ROOT).startsWith("new")) {
            return NEW;
        }
        return ACTIVE;
    }

    /**
     * The availability of {@code part} for an order of {@code quantity} pieces; {@code full} adds notes that do not
     * affect availability (TME {@code NEW} and {@code PROMOTED}, the JLCPCB library type, Mouser reels).
     */
    public static Availability of(Part part, int quantity, boolean full) {
        return of(part, quantity, full, DEFAULT_LOW_STOCK_THRESHOLD);
    }

    /**
     * True when the part's stock is low for an order of {@code quantity}: below {@code threshold} pieces or below twice
     * the quantity (but not below the quantity itself, which is {@value #LIMITED}).
     */
    public static boolean isLowStock(Part part, int quantity, int threshold) {
        int qty = Math.max(1, quantity);
        return part.stock() >= qty && (part.stock() < threshold || part.stock() < 2L * qty);
    }

    /** As {@link #of(Part, int, boolean)} with the given {@code kina.search.low-stock-threshold}. */
    public static Availability of(Part part, int quantity, boolean full, int lowStockThreshold) {
        String status = IN_STOCK;
        List<String> notes = new ArrayList<>();
        Set<String> tme = statuses(part.extra().get("product_status"));
        if (tme.contains("ONLY_FOR_SPECIAL_ORDER")) {
            status = worst(status, SPECIAL_ORDER);
            notes.add("TME sells it only by special order, not from stock.");
        }
        if (tme.contains("CANNOT_BE_ORDERED")) {
            status = worst(status, SPECIAL_ORDER);
            notes.add("TME does not sell it in this country.");
        }
        if (tme.contains("EXTERNAL_WAREHOUSE")) {
            status = worst(status, EXTERNAL_WAREHOUSE);
            notes.add("TME ships it from an external warehouse, with a longer delivery time.");
        }
        if (tme.contains("AVAILABLE_WHILE_STOCKS_LAST")) {
            status = worst(status, LAST_UNITS);
            notes.add("TME sells it only while stocks last; it will not be restocked.");
        }
        if (tme.contains("HARDLY_AVAILABLE")) {
            notes.add("TME flags limited market availability (a supply warning, not low stock).");
        }
        if (tme.contains("MOQ_VALID_WHILE_STOCKS_LAST")) {
            notes.add("TME says the minimum order quantity may change once this stock is sold out.");
        }
        if (tme.contains("DANGEROUS") || tme.contains("OVERSIZED")) {
            notes.add("TME applies shipping restrictions (" + (tme.contains("DANGEROUS") ? "dangerous goods"
                    : "oversized") + ").");
        }
        if (full && tme.contains("NEW")) {
            notes.add("TME added it to the catalogue recently.");
        }
        if (full && tme.contains("PROMOTED")) {
            notes.add("TME promotes it.");
        }
        String lifecycle = mouserLifecycle(part);
        if (lifecycle != null) {
            if (MOUSER_END_OF_LIFE.contains(lifecycle.toLowerCase(Locale.ROOT))) {
                status = worst(status, LAST_UNITS);
                notes.add("Mouser lifecycle status: " + lifecycle + ".");
            } else if (full) {
                notes.add("Mouser lifecycle status: " + lifecycle + ".");
            }
        }
        Integer maxOrder = integer(part.extra().get("sales_maximum_order_qty"));
        if (maxOrder != null && maxOrder > 0 && (full || quantity > maxOrder)) {
            notes.add("Mouser sells at most " + maxOrder + " per order.");
        }
        if (full && Boolean.TRUE.equals(part.extra().get("reeling"))) {
            notes.add("Mouser also cuts custom reels (MouseReel).");
        }
        Object library = part.extra().get("library_type");
        if (full && library != null && !library.toString().isBlank()) {
            notes.add("JLCPCB assembly library: " + library.toString().strip() + ".");
        }
        if (part.stock() < quantity) {
            status = worst(status, LIMITED);
            notes.addFirst("Only " + part.stock() + " ship now, fewer than the " + quantity + " requested.");
        } else if (isLowStock(part, quantity, lowStockThreshold)) {
            status = worst(status, LOW_STOCK);
            notes.addFirst("Only " + part.stock() + " in stock" + (quantity > 1 ? ", less than twice the " + quantity
                    + " requested." : "."));
        } else if (IN_STOCK.equals(status)) {
            notes.addFirst("Ships now from stock.");
        }
        return new Availability(status, String.join(" ", notes));
    }

    private static String mouserLifecycle(Part part) {
        Object lifecycle = part.extra().get("lifecycle_status");
        return lifecycle == null || lifecycle.toString().isBlank() ? null : lifecycle.toString().strip();
    }

    private static String worst(String a, String b) {
        return SEVERITY.indexOf(a) <= SEVERITY.indexOf(b) ? a : b;
    }

    private static Set<String> statuses(Object raw) {
        if (raw instanceof Collection<?> c) {
            return c.stream().filter(x -> x != null).map(x -> x.toString().strip().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
        }
        return raw == null ? Set.of() : Set.of(raw.toString().strip().toUpperCase(Locale.ROOT));
    }

    private static Integer integer(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        try {
            return raw == null ? null : Integer.valueOf(raw.toString().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
