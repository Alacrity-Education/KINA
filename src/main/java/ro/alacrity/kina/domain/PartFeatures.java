package ro.alacrity.kina.domain;

import java.util.List;

/**
 * What KINA reads from a part for matching (DESIGN.md 3.4), as {@link ConstraintKind} compares it with a request.
 * Every attribute is null when the part does not state it.
 */
public interface PartFeatures {

    /** A numeric attribute of a part: its value in SI base units, its display form and its test condition. */
    interface Measure {

        double value();

        String display();

        /** The test frequency of an impedance, the temperature of a lifetime; else null. */
        Double condition();
    }

    String family();

    /** The value of {@code kind} (a {@link ParsedQuery} constraint kind), null when the part does not state it. */
    Measure measure(String kind);

    /** The numeric value of {@code kind}, null when the part does not state it. */
    default Double value(String kind) {
        Measure m = measure(kind);
        return m == null ? null : m.value();
    }

    String dielectric();

    String packageName();

    String mounting();

    String technology();

    Integer elements();

    String polarity();

    String subtype();

    String formFactor();

    ParsedQuery.Connector connector();

    /** The voltages the part states as its specification (Zener voltage, output voltages), never null. */
    List<Double> voltages();

    /** Lower-case text of the part (description, category, attributes) for word matches. */
    String text();
}
