package ro.alacrity.kina.metrics;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Identity of one stored counter value (DESIGN.md 3.7): the Micrometer meter name plus its tags in canonical form,
 * {@code key=value} pairs sorted by key and joined with commas ({@code distributor=MOUSER,outcome=ok}; empty without
 * tags). The canonical form is the {@code tags} column of {@code metrics_counters}, so the same counter always maps to
 * the same row. Commas, equals signs and line breaks inside a key or value become {@code _}; an empty value becomes
 * {@code none}.
 *
 * @param name Micrometer name, e.g. {@code kina.searches}; a timer is stored as two keys, {@code <name>:count} and
 *             {@code <name>:nanos}
 * @param tags canonical tag string, never null
 */
public record MetricKey(String name, String tags) implements Comparable<MetricKey> {

    public MetricKey {
        Objects.requireNonNull(name, "name");
        tags = tags == null ? "" : tags;
    }

    /** A key from alternating tag keys and values ({@code "distributor", "MOUSER", "outcome", "ok"}). */
    public static MetricKey of(String name, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("tags must be key/value pairs");
        }
        Map<String, String> tags = new TreeMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            tags.put(clean(keyValues[i]), clean(keyValues[i + 1]));
        }
        return new MetricKey(name, canonical(tags));
    }

    /** The canonical tag string of {@code tags}, sorted by key. */
    static String canonical(Map<String, String> tags) {
        StringBuilder out = new StringBuilder();
        new TreeMap<>(tags).forEach((k, v) -> out.append(out.isEmpty() ? "" : ",").append(k).append('=').append(v));
        return out.toString();
    }

    /** The tags as an ordered map (parsed back from the canonical string). */
    public Map<String, String> tagMap() {
        Map<String, String> out = new LinkedHashMap<>();
        if (tags.isEmpty()) {
            return out;
        }
        for (String pair : tags.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return out;
    }

    /** The tags for Micrometer. */
    public Tags micrometerTags() {
        List<Tag> list = new ArrayList<>();
        tagMap().forEach((k, v) -> list.add(Tag.of(k, v)));
        return Tags.of(list);
    }

    /** The value of one tag, or null. */
    public String tag(String key) {
        return tagMap().get(key);
    }

    /** This key with another name and the same tags. */
    public MetricKey withName(String newName) {
        return new MetricKey(newName, tags);
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) {
            return "none";
        }
        return value.replace(',', '_').replace('=', '_').replace('\n', '_').replace('\r', '_');
    }

    @Override
    public int compareTo(MetricKey other) {
        int byName = name.compareTo(other.name);
        return byName != 0 ? byName : tags.compareTo(other.tags);
    }

    @Override
    public String toString() {
        return tags.isEmpty() ? name : name + "{" + tags + "}";
    }
}
