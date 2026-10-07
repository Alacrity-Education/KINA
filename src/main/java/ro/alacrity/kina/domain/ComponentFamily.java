package ro.alacrity.kina.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static ro.alacrity.kina.domain.ComponentFamily.Trait.ARRAYS;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.FREQUENCY_VALUED;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.INDUCTIVE;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.LARGEST_VOLTAGE;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.PASSIVE;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.POLARISED;

/**
 * The component families the parser can name (DESIGN.md 3.4), with what the rest of KINA needs to know about each:
 * the generic family it specialises ({@link #parent()}), its row of the hard-constraint table ({@link #policy()}) and
 * its {@link Trait traits}. Families travel as their {@link #label()} ({@link ParsedQuery#family()}, the
 * {@code Family} attribute, the metrics {@code type} tag); {@link #of(String)} reads one back. The words that name a
 * family are the vocabulary of {@code search.Recognizers}.
 */
public enum ComponentFamily {

    RESISTOR("resistor", PolicyFamily.RESISTOR, PASSIVE, ARRAYS),
    CAPACITOR("capacitor", PolicyFamily.CAPACITOR, PASSIVE, ARRAYS),
    INDUCTOR("inductor", PolicyFamily.INDUCTOR, PASSIVE, INDUCTIVE),
    /** Ferrite beads. */
    FERRITE("ferrite", PolicyFamily.FERRITE, PASSIVE, ARRAYS, INDUCTIVE),
    /** Passive resonators; never mixed with oscillators. */
    CRYSTAL("crystal", PolicyFamily.CRYSTAL, FREQUENCY_VALUED),
    /** Active oscillators (with a supply). */
    OSCILLATOR("oscillator", PolicyFamily.OSCILLATOR, FREQUENCY_VALUED),
    /** The generic diode: standard rectifiers and switching diodes, or a diode of unknown kind. */
    DIODE("diode", PolicyFamily.DIODE, LARGEST_VOLTAGE),
    SCHOTTKY("schottky", DIODE, LARGEST_VOLTAGE),
    ZENER("zener", DIODE),
    TVS("tvs", DIODE),
    /** Light-emitting diodes: a specialisation of the diode with a policy row of its own (colour, LED type). */
    LED("led", DIODE, PolicyFamily.LED),
    /** The generic transistor (bipolar, or of unknown kind). */
    TRANSISTOR("transistor", PolicyFamily.TRANSISTOR, POLARISED, LARGEST_VOLTAGE),
    MOSFET("mosfet", TRANSISTOR, POLARISED, LARGEST_VOLTAGE),
    /** Gate drivers, GaN power stages and half-bridges with an integrated driver. */
    GATE_DRIVER("gate driver", PolicyFamily.DEFAULT),
    REGULATOR("regulator", PolicyFamily.REGULATOR),
    OPAMP("opamp", PolicyFamily.DEFAULT),
    COMPARATOR("comparator", PolicyFamily.DEFAULT),
    MCU("mcu", PolicyFamily.DEFAULT),
    CONNECTOR("connector", PolicyFamily.CONNECTOR),
    FUSE("fuse", PolicyFamily.DEFAULT),
    RELAY("relay", PolicyFamily.DEFAULT),
    /** Mechanical switches: tactile, push-button, toggle, slide, rocker, DIP, rotary, snap action, reed... */
    SWITCH("switch", PolicyFamily.SWITCH),
    /** Fans and blowers: axial or radial, with a frame size, an exact supply voltage and airflow ratings. */
    FAN("fan", PolicyFamily.FAN);

    /** What a family's parts have in common. */
    public enum Trait {
        /** Resistors, capacitors, inductors and ferrite beads: chip package codes, form factor classes, body
         * dimensions, qualification and features. */
        PASSIVE,
        /** Arrays and networks are told apart from single elements. */
        ARRAYS,
        /** An ohm value is an impedance or a DC resistance, never a nominal resistance; a current is a rated one. */
        INDUCTIVE,
        /** The primary value is the frequency (a capacitance is the load capacitance). */
        FREQUENCY_VALUED,
        /** Unlabelled description voltages are read as the largest one (DESIGN.md 3.4 "Voltage of transistors and
         * diodes"). */
        LARGEST_VOLTAGE,
        /** Parts have a polarity (N-channel, NPN...) and an on-resistance. */
        POLARISED
    }

    private final String label;
    private final ComponentFamily parent;
    private final PolicyFamily policy;
    private final Set<Trait> traits;

    ComponentFamily(String label, PolicyFamily policy, Trait... traits) {
        this(label, null, policy, traits);
    }

    /** A specialisation of {@code parent}, in its policy family. */
    ComponentFamily(String label, ComponentFamily parent, Trait... traits) {
        this(label, parent, parent.policy, traits);
    }

    ComponentFamily(String label, ComponentFamily parent, PolicyFamily policy, Trait... traits) {
        this.label = label;
        this.parent = parent;
        this.policy = policy;
        Set<Trait> set = EnumSet.noneOf(Trait.class);
        Collections.addAll(set, traits);
        this.traits = Collections.unmodifiableSet(set);
    }

    /** The name the family travels as ({@code capacitor}, {@code gate driver}). */
    public String label() {
        return label;
    }

    /** The generic family this one specialises ({@link #SCHOTTKY} -&gt; {@link #DIODE}), else null. */
    public ComponentFamily parent() {
        return parent;
    }

    /** The row of the hard-constraint table. */
    public PolicyFamily policy() {
        return policy;
    }

    public boolean has(Trait trait) {
        return traits.contains(trait);
    }

    /** The family of a label, null for null or an unknown label. */
    public static ComponentFamily of(String label) {
        if (label != null) {
            for (ComponentFamily family : values()) {
                if (family.label.equals(label)) {
                    return family;
                }
            }
        }
        return null;
    }

    /** True when the family of {@code label} has {@code trait}; false for null or an unknown label. */
    public static boolean has(String label, Trait trait) {
        ComponentFamily family = of(label);
        return family != null && family.has(trait);
    }

    /**
     * True when a request of family {@code wanted} accepts a part of family {@code actual}: the same family, or one is
     * the generic family of the other ({@code diode} and {@code schottky}, {@code transistor} and {@code mosfet}).
     * Unknown families never conflict. Crystals and oscillators are different families.
     */
    public static boolean compatible(String wanted, String actual) {
        if (wanted == null || actual == null || wanted.equals(actual)) {
            return true;
        }
        return wanted.equals(parentOf(actual)) || actual.equals(parentOf(wanted));
    }

    /** The label of the generic family of {@code label} ({@code schottky} -&gt; {@code diode}), else null. */
    public static String parentOf(String label) {
        ComponentFamily family = of(label);
        return family == null || family.parent == null ? null : family.parent.label;
    }
}
