package ro.alacrity.kina.domain;

import java.util.Locale;

/**
 * The policy families of the hard-constraint table (DESIGN.md 3.4), in table order: the entries of
 * {@link Relax#families()} and {@link Overshoot#families()}; their {@link #key()} is the key of
 * {@code kina.search.hard-constraints}.
 */
public enum PolicyFamily {

    RESISTOR, CAPACITOR, INDUCTOR, FERRITE, CRYSTAL, OSCILLATOR,
    /** Diodes of every kind: standard, Schottky, Zener, TVS, LED. */
    DIODE,
    /** Transistors and MOSFETs. */
    TRANSISTOR,
    REGULATOR, CONNECTOR, USB,
    /** Every other family, and requests whose family is not known. */
    DEFAULT;

    /** The configuration and wire name ({@code resistor}, {@code usb}, {@code default}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The family of a configuration key (any case), null when none has it. */
    public static PolicyFamily byKey(String key) {
        for (PolicyFamily family : values()) {
            if (family.key().equalsIgnoreCase(key)) {
                return family;
            }
        }
        return null;
    }

    /**
     * The policy family of a request: {@link #USB} or {@link #CONNECTOR} for a connector request, else the policy
     * family of its component family ({@link ComponentFamily#policy()}), {@link #DEFAULT} when that is not known.
     */
    public static PolicyFamily of(ParsedQuery query) {
        if (query == null) {
            return DEFAULT;
        }
        if (query.isConnector()) {
            return query.connector().isUsb() ? USB : CONNECTOR;
        }
        ComponentFamily family = ComponentFamily.of(query.family());
        return family == null ? DEFAULT : family.policy();
    }
}
