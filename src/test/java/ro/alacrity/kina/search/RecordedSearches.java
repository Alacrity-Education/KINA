package ro.alacrity.kina.search;

import lombok.experimental.UtilityClass;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Recorded searches of {@code fixtures/<family>/<name>.json} (live probes of 2026-10-07: LEDs in {@code leds}, switches
 * in {@code switches}): the query, the phrase each distributor received and its parts with their raw attributes.
 */
@UtilityClass
class RecordedSearches {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The recorded parts per distributor. */
    static Map<Distributor, List<Part>> load(String family, String name) {
        JsonNode root = root(family, name);
        Map<Distributor, List<Part>> out = new EnumMap<>(Distributor.class);
        root.get("parts").properties().forEach(e -> {
            List<Part> parts = new ArrayList<>();
            e.getValue().forEach(node -> parts.add(MAPPER.treeToValue(node, Part.class)));
            out.put(Distributor.valueOf(e.getKey()), parts);
        });
        return out;
    }

    /** The phrase each distributor received. */
    static Map<Distributor, String> phrases(String family, String name) {
        Map<Distributor, String> out = new EnumMap<>(Distributor.class);
        root(family, name).get("phrases").properties()
                .forEach(e -> out.put(Distributor.valueOf(e.getKey()), e.getValue().asString()));
        return out;
    }

    /** One recorded part by its manufacturer part number. */
    static Part part(String family, String name, Distributor distributor, String mpn) {
        return load(family, name).get(distributor).stream().filter(p -> mpn.equals(p.manufacturerPartNumber()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(mpn + " not in " + name + " at "
                        + distributor));
    }

    /** Every recorded part of the named fixtures. */
    static List<Part> all(String family, List<String> names) {
        List<Part> out = new ArrayList<>();
        names.forEach(name -> load(family, name).values().forEach(out::addAll));
        return out;
    }

    private static JsonNode root(String family, String name) {
        try (InputStream in = RecordedSearches.class.getResourceAsStream("/fixtures/" + family + "/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + family + "/" + name);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
