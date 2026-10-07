package ro.alacrity.kina.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PartAttributeTest {

    @Test
    void namesAreLowerCaseTrimmedAndNotRepeatedWithinASource() {
        for (PartAttribute a : PartAttribute.values()) {
            for (Source s : a.sources()) {
                Set<String> seen = new HashSet<>();
                for (String name : s.names()) {
                    assertThat(name).as(a + " " + name).isEqualTo(name.toLowerCase(Locale.ROOT));
                    assertThat(seen.add(name)).as(a + " repeats " + name).isTrue();
                }
            }
        }
    }

    @Test
    void unitSymbolsAreLowerCaseAndOwnedOnce() {
        assertThat(PartAttribute.unitSymbols()).containsEntry("vdc", ParsedQuery.VOLTAGE)
                .containsEntry("ohms", ParsedQuery.RESISTANCE).containsEntry("hz", ParsedQuery.FREQUENCY);
        PartAttribute.unitSymbols().keySet().forEach(s -> assertThat(s).isEqualTo(s.toLowerCase(Locale.ROOT)));
    }

    @Test
    void attributesWithoutSymbolsAreReadInTheUnitOfTheirOwner() {
        assertThat(PartAttribute.DCR.readKind()).isEqualTo(ParsedQuery.RESISTANCE);
        assertThat(PartAttribute.SATURATION_CURRENT.readKind()).isEqualTo(ParsedQuery.CURRENT);
        assertThat(PartAttribute.TOLERANCE.readKind()).isEqualTo(ParsedQuery.TOLERANCE);
    }

    @Test
    void reportedAttributesHaveDistinctKeysAndKinds() {
        assertThat(PartAttribute.VALUES.stream().map(PartAttribute::key).distinct()).hasSize(PartAttribute.VALUES.size());
        assertThat(PartAttribute.VALUES.stream().map(PartAttribute::kind).distinct())
                .hasSize(PartAttribute.VALUES.size());
        assertThat(Arrays.stream(PartAttribute.values()).filter(a -> a.unit() != null))
                .allMatch(a -> a.kind() != null);
    }
}
