package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.PolicyFamily;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The family table of {@link ComponentFamily}: the vocabulary of {@link Recognizers} names exactly its families, the
 * traits are the sets the search package used before the table existed, and DESIGN.md 3.7 lists the labels.
 */
class ComponentFamilyTest {

    @Test
    void theParserNamesExactlyTheDeclaredFamilies() {
        assertThat(Recognizers.families()).containsExactlyInAnyOrderElementsOf(labels(ComponentFamily.values()));
    }

    @Test
    void traitsAreTheFormerFamilySets() {
        assertThat(withTrait(Trait.PASSIVE)).containsExactlyInAnyOrder("resistor", "capacitor", "inductor", "ferrite");
        assertThat(withTrait(Trait.ARRAYS)).containsExactlyInAnyOrder("resistor", "capacitor", "ferrite");
        assertThat(withTrait(Trait.INDUCTIVE)).containsExactlyInAnyOrder("inductor", "ferrite");
        assertThat(withTrait(Trait.FREQUENCY_VALUED)).containsExactlyInAnyOrder("crystal", "oscillator");
        assertThat(withTrait(Trait.LARGEST_VOLTAGE)).containsExactlyInAnyOrder("transistor", "mosfet", "diode",
                "schottky");
        assertThat(withTrait(Trait.POLARISED)).containsExactlyInAnyOrder("mosfet", "transistor");
        assertThat(ComponentFamily.has(null, Trait.PASSIVE)).isFalse();
        assertThat(ComponentFamily.has("thermistor", Trait.PASSIVE)).isFalse();
    }

    @Test
    void parentsAndPolicyFamiliesAreTheFormerTables() {
        Map<String, String> parents = Arrays.stream(ComponentFamily.values()).filter(f -> f.parent() != null)
                .collect(Collectors.toMap(ComponentFamily::label, f -> f.parent().label()));
        assertThat(parents).isEqualTo(Map.of("schottky", "diode", "zener", "diode", "tvs", "diode", "led", "diode",
                "mosfet", "transistor"));
        for (ComponentFamily family : ComponentFamily.values()) {
            PolicyFamily expected = switch (family.label()) {
                case "resistor", "capacitor", "inductor", "ferrite", "crystal", "oscillator", "regulator",
                     "connector", "fan" -> PolicyFamily.byKey(family.label());
                case "diode", "schottky", "zener", "tvs", "led" -> PolicyFamily.DIODE;
                case "transistor", "mosfet" -> PolicyFamily.TRANSISTOR;
                default -> PolicyFamily.DEFAULT;
            };
            assertThat(family.policy()).as(family.label()).isEqualTo(expected);
        }
        assertThat(ComponentFamily.compatible("diode", "schottky")).isTrue();
        assertThat(ComponentFamily.compatible("mosfet", "transistor")).isTrue();
        assertThat(ComponentFamily.compatible("crystal", "oscillator")).isFalse();
        assertThat(ComponentFamily.compatible("zener", "tvs")).isFalse();
        assertThat(ComponentFamily.compatible(null, "tvs")).isTrue();
    }

    @Test
    void designListsTheFamiliesAsMetricTypes() throws IOException {
        String design = Files.readString(Path.of("docs/DESIGN.md"), StandardCharsets.UTF_8).replace('\n', ' ');
        Matcher m = Pattern.compile("case, one of (.*?) \\(the labels of `domain\\.ComponentFamily`").matcher(design);
        assertThat(m.find()).as("the type tag list in DESIGN.md 3.7").isTrue();
        Set<String> documented = new LinkedHashSet<>();
        Matcher name = Pattern.compile("`([a-z ]+)`").matcher(m.group(1));
        while (name.find()) {
            documented.add(name.group(1));
        }
        assertThat(documented).containsExactlyElementsOf(labels(ComponentFamily.values()));
    }

    private static Set<String> withTrait(Trait trait) {
        return Arrays.stream(ComponentFamily.values()).filter(f -> f.has(trait)).map(ComponentFamily::label)
                .collect(Collectors.toSet());
    }

    private static Set<String> labels(ComponentFamily... families) {
        return Arrays.stream(families).map(ComponentFamily::label)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
