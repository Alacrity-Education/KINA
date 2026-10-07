package ro.alacrity.kina.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A part as the attribute logic reads it (DESIGN.md 3.4): its fields and its distributor attributes with trimmed,
 * lower-cased names (the first spelling wins; blank values are left out).
 *
 * @param attributes the distributor attributes by lower-case name, in the part's order
 */
public record PartSource(Part part, Map<String, String> attributes) {

    public PartSource {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** The part with its attributes keyed by lower-case name. */
    public static PartSource of(Part part) {
        Map<String, String> attrs = new LinkedHashMap<>();
        part.attributes().forEach((k, v) -> {
            if (k != null && v != null && !v.isBlank()) {
                attrs.putIfAbsent(k.trim().toLowerCase(Locale.ROOT), v);
            }
        });
        return new PartSource(part, attrs);
    }

    /** The value of a lower-case attribute name, null when the part does not have it. */
    public String attribute(String name) {
        return attributes.get(name);
    }

    /** The values of the names the part has, in the order of the names. */
    public List<String> values(Iterable<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) {
            String v = attributes.get(name);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }

    public Distributor distributor() {
        return part.distributor();
    }

    public String description() {
        return part.description();
    }

    public String category() {
        return part.category();
    }

    public String packageName() {
        return part.packageName();
    }

    public String manufacturer() {
        return part.manufacturer();
    }

    public String manufacturerPartNumber() {
        return part.manufacturerPartNumber();
    }
}
