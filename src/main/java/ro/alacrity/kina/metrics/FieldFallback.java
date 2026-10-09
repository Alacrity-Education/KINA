package ro.alacrity.kina.metrics;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Why a Mouser or TME search took the cached-search path instead of the field-first flow: the {@code reason} tag of
 * {@link Metric#FIELD_FALLBACKS} (DESIGN.md 3.7, 3.2 "Field-first flow"). The help text of the metric and the DESIGN.md
 * row are checked against these values ({@code MetricDocumentationTest}).
 */
public enum FieldFallback {
    /** The mode is not {@code on}. */
    MODE,
    /** The request set {@code bypass_cache}. */
    BYPASS,
    /** The index is not complete for the distributor. */
    INCOMPLETE,
    /** The request states nothing but its family (DESIGN.md 3.2 "The stated-constraint rule"). */
    GENERIC,
    /** The field query or the journal could not be read (also an {@code augment} failure). */
    SQL_ERROR;

    /** The tag value: {@code mode}, {@code sql_error}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Every tag value, in declaration order. */
    public static List<String> codes() {
        return Arrays.stream(values()).map(FieldFallback::code).toList();
    }
}
