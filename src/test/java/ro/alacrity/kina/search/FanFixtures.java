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
 * The recorded fan searches of {@code fixtures/fans/} (live probe of 2026-10-07, one Mouser and one TME search per
 * query; DESIGN.md 3.4 "Fans"): the parts per distributor with their raw attributes.
 */
@UtilityClass
class FanFixtures {

    /** The four probe queries and their fixture names. */
    static final Map<String, String> QUERIES = Map.of(
            "40x40x10 fan 12V", "40x40x10-fan-12v",
            "120mm axial fan 12V PWM", "120mm-axial-fan-12v-pwm",
            "radial blower 24V", "radial-blower-24v",
            "fan 5V 3000rpm", "fan-5v-3000rpm");

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** The recorded parts of {@code fixtures/fans/<name>.json} per distributor. */
    static Map<Distributor, List<Part>> load(String name) {
        try (InputStream in = FanFixtures.class.getResourceAsStream("/fixtures/fans/" + name + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            JsonNode root = MAPPER.readTree(in);
            Map<Distributor, List<Part>> out = new EnumMap<>(Distributor.class);
            root.get("parts").properties().forEach(e -> {
                List<Part> parts = new ArrayList<>();
                e.getValue().forEach(node -> parts.add(MAPPER.treeToValue(node, Part.class)));
                out.put(Distributor.valueOf(e.getKey()), parts);
            });
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One recorded part by its manufacturer part number. */
    static Part part(String name, Distributor distributor, String mpn) {
        return load(name).get(distributor).stream().filter(p -> mpn.equals(p.manufacturerPartNumber())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(mpn + " not in " + name + " at " + distributor));
    }

    /** Every recorded part of every fixture. */
    static List<Part> all() {
        List<Part> out = new ArrayList<>();
        QUERIES.values().stream().sorted().forEach(name -> load(name).values().forEach(out::addAll));
        return out;
    }
}
