package ro.alacrity.kina.domain;

import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * The policy families of the hard-constraint table (DESIGN.md 3.4): the keys of
 * {@code kina.search.hard-constraints} and of {@link Relax#families()}.
 */
@UtilityClass
public class PolicyFamily {

    public static final String RESISTOR = "resistor";
    public static final String CAPACITOR = "capacitor";
    public static final String INDUCTOR = "inductor";
    public static final String FERRITE = "ferrite";
    public static final String CRYSTAL = "crystal";
    public static final String OSCILLATOR = "oscillator";
    /** Diodes of every kind: standard, Schottky, Zener, TVS, LED. */
    public static final String DIODE = "diode";
    /** Transistors and MOSFETs. */
    public static final String TRANSISTOR = "transistor";
    public static final String REGULATOR = "regulator";
    public static final String CONNECTOR = "connector";
    public static final String USB = "usb";
    /** Every other family, and requests whose family is not known. */
    public static final String DEFAULT = "default";

    /** Every policy family, in table order. */
    public static final List<String> ALL = List.of(RESISTOR, CAPACITOR, INDUCTOR, FERRITE, CRYSTAL, OSCILLATOR, DIODE,
            TRANSISTOR, REGULATOR, CONNECTOR, USB, DEFAULT);

    /** The policy family of a request: its component family, {@link #USB} for USB connectors, else {@link #DEFAULT}. */
    public static String of(ParsedQuery query) {
        if (query == null) {
            return DEFAULT;
        }
        if (query.isConnector()) {
            return query.connector().isUsb() ? USB : CONNECTOR;
        }
        String family = query.family();
        if (family == null) {
            return DEFAULT;
        }
        return switch (family) {
            case RESISTOR, CAPACITOR, INDUCTOR, FERRITE, CRYSTAL, OSCILLATOR, REGULATOR, CONNECTOR -> family;
            case "diode", "schottky", "zener", "tvs", "led" -> DIODE;
            case "transistor", "mosfet" -> TRANSISTOR;
            default -> DEFAULT;
        };
    }
}
