package ro.alacrity.kina.domain;

import ro.alacrity.kina.domain.ComponentFamily.Trait;
import ro.alacrity.kina.domain.ParsedQuery.Connector;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ro.alacrity.kina.domain.Match.Scope.CONNECTOR;
import static ro.alacrity.kina.domain.Match.Scope.PART;
import static ro.alacrity.kina.domain.Match.Scope.USB;
import static ro.alacrity.kina.domain.MatchMode.AT_LEAST;
import static ro.alacrity.kina.domain.MatchMode.AT_MOST;
import static ro.alacrity.kina.domain.MatchMode.COMPATIBLE;
import static ro.alacrity.kina.domain.MatchMode.CUSTOM;
import static ro.alacrity.kina.domain.MatchMode.EQUAL;
import static ro.alacrity.kina.domain.MatchMode.EQUAL_IGNORE_CASE;
import static ro.alacrity.kina.domain.MatchMode.FEATURE;
import static ro.alacrity.kina.domain.MatchMode.WITHIN;
import static ro.alacrity.kina.domain.PolicyFamily.CAPACITOR;
import static ro.alacrity.kina.domain.PolicyFamily.CRYSTAL;
import static ro.alacrity.kina.domain.PolicyFamily.DEFAULT;
import static ro.alacrity.kina.domain.PolicyFamily.DIODE;
import static ro.alacrity.kina.domain.PolicyFamily.FAN;
import static ro.alacrity.kina.domain.PolicyFamily.FERRITE;
import static ro.alacrity.kina.domain.PolicyFamily.INDUCTOR;
import static ro.alacrity.kina.domain.PolicyFamily.LED;
import static ro.alacrity.kina.domain.PolicyFamily.OSCILLATOR;
import static ro.alacrity.kina.domain.PolicyFamily.REGULATOR;
import static ro.alacrity.kina.domain.PolicyFamily.RESISTOR;
import static ro.alacrity.kina.domain.PolicyFamily.TRANSISTOR;
import static ro.alacrity.kina.domain.RelaxStrategy.BELOW_SPEC;
import static ro.alacrity.kina.domain.RelaxStrategy.LADDER;
import static ro.alacrity.kina.domain.RelaxStrategy.NEVER;
import static ro.alacrity.kina.domain.RelaxStrategy.PREFERENCE;
import static ro.alacrity.kina.domain.RelaxStrategy.SOFT;

/**
 * Every attribute a request can state and a part can match, with its relaxation policy ({@link Relax}) and its
 * matching rule ({@link Match}) declared on the constant (DESIGN.md 3.2 to 3.4). The hard-constraint table, the
 * relaxation ladder, the exclusion check, the deterministic score, the match grade and the reported mismatches are
 * all read from these declarations; change a rule here, not in the ranker or the policy.
 *
 * <p>The constants are declared in check order: the first hard constraint a part contradicts is the one it is counted
 * under in {@code excluded_by_constraints_detail}. {@link Match#order()} is the order of the score (and of the
 * {@code unverified} list), {@link Match#report()} the order of the {@code mismatches}.
 *
 * <p>The {@link #label()} is the wire and configuration name ({@code kina.search.hard-constraints}, reported lists).
 * Kinds that share a label are one attribute matched differently by request: the exact voltage of a Zener or a fixed
 * regulator and the minimum voltage rating of everything else; the gender, orientation and mounting of USB and of
 * other connectors. The policy names ({@link #policyKinds()}) are the labels of the kinds a family can make hard.
 *
 * <p>A numeric kind declared for some families ({@code onlyFor}) is that measure's rule for those families, and the
 * general kind of the measure leaves them alone: the exact voltage of a Zener diode, a fixed regulator or a fan, the
 * exact current of a fuse, the maximum current of a fan.
 */
public enum ConstraintKind {

    // ---------------------------------------------------------------- the policy kinds, in check order

    /**
     * The component type: the family (crystal vs oscillator, Schottky vs Zener...), a standard rectifier against a
     * specialised diode, a fixed against an adjustable regulator. Scored as the family signal.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, allFamilies = true)
    @Match(mode = CUSTOM, weight = 0.05, order = 28, report = 17)
    TYPE("type", ParsedQuery::family, PartFeatures::family) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            return false;   // every request has a type
        }

        @Override
        public Outcome score(MatchContext c, double weight) {
            return c.query().family() == null ? Outcome.NOT_STATED
                    : Outcome.counted(weight * familyGrade(c), weight);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            return typeConflict(c) ? Verdict.CONFLICT : Verdict.MATCH;
        }

        @Override
        public String mismatch(MatchContext c) {
            ParsedQuery q = c.query();
            return q.family() != null && familyGrade(c) < 0
                    ? "family: " + c.part().family() + " instead of " + q.family() : null;
        }
    },

    /** Transistor polarity (N-channel, P-channel, NPN, PNP). */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {TRANSISTOR, DEFAULT})
    @Match(mode = EQUAL, weight = 0.10, order = 12, report = 3)
    POLARITY("polarity", ParsedQuery::polarity, PartFeatures::polarity),

    /**
     * The primary value: capacitance, resistance, inductance, the impedance of a ferrite bead (at its test frequency
     * when both sides state one), the frequency of a crystal or oscillator; within 1 %. Reported by its kind.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {RESISTOR, CAPACITOR, INDUCTOR, FERRITE, CRYSTAL, OSCILLATOR, DEFAULT})
    @Match(mode = WITHIN, tolerance = 0.01, weight = 0.30, order = 10, report = 1)
    VALUE("value", q -> {
        String primary = q.isConnector() ? null : primaryKind(q);
        return primary == null ? null : q.constraint(primary);
    }, f -> null) {
        @Override
        public Object actual(ParsedQuery q, PartFeatures f) {
            String primary = q.isConnector() ? null : primaryKind(q);
            return primary == null ? null : f.measure(primary);
        }

        @Override
        public String reported(ParsedQuery q) {
            String primary = q.isConnector() ? null : primaryKind(q);
            return primary == null ? label() : primary.replace('_', ' ');
        }

        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            ParsedQuery.Constraint w = (ParsedQuery.Constraint) wanted;
            PartFeatures.Measure a = (PartFeatures.Measure) actual;
            double tolerance = match().tolerance();
            return grade(sameValue(w.value(), a.value(), tolerance)
                    && (w.condition() == null || a.condition() == null
                    || sameValue(w.condition(), a.condition(), tolerance)));
        }
    },

    /**
     * The exact voltage of a Zener diode, a fixed regulator or a fan (within 2 %: a fan runs from its supply, a 24 V fan
     * is no 12 V fan): one of the voltages the part states as its specification. A rating of the group shared with the
     * minimum ratings.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {DIODE, REGULATOR, FAN, DEFAULT})
    @Match(mode = CUSTOM, tolerance = 0.02, weight = 0.10, group = Match.RATING, order = 16, report = 7)
    EXACT_VOLTAGE("voltage", ParsedQuery.VOLTAGE, ComponentFamily.REGULATOR, ComponentFamily.ZENER,
            ComponentFamily.FAN) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            Boolean same = exactVoltage(c);
            return same == null ? null : grade(same);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            return exactVoltage(c) == Boolean.FALSE ? Verdict.CONFLICT : Verdict.MATCH;
        }
    },

    /** The load capacitance of a crystal (a capacitance in a crystal request), within 1 %. */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = CRYSTAL)
    @Match(mode = WITHIN, tolerance = 0.01, weight = 0.10, order = 13, report = 2)
    LOAD_CAPACITANCE("load capacitance", q -> isLoadCapacitance(q) ? q.constraint(ParsedQuery.CAPACITANCE) : null,
            f -> f.measure(ParsedQuery.CAPACITANCE)),

    /**
     * The package: equivalent names (imperial chip codes, SOT-23-3 == SOT-23, can sizes within 0.2 mm); a package KINA
     * cannot read is unverified, never a conflict.
     */
    @Relax(strategy = LADDER, order = 1)
    @Relax(strategy = NEVER, families = {RESISTOR, CAPACITOR, FERRITE, DIODE, LED, TRANSISTOR, REGULATOR,
            PolicyFamily.CONNECTOR, DEFAULT})
    @Match(mode = CUSTOM, weight = 0.20, order = 11, report = 4)
    PACKAGE("package", ParsedQuery::packageName, PartFeatures::packageName) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            Boolean same = c.samePackage((String) wanted, (String) actual);
            return same == null ? null : grade(same);
        }
    },

    /**
     * SMD or THT. A hybrid USB part (SMD signal pins, through-hole shell legs) is unknown for the check. Scored here
     * for parts; connector and USB requests score it as {@link #CONNECTOR_MOUNTING} and {@link #USB_MOUNTING}.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, allFamilies = true)
    @Match(mode = EQUAL, weight = 0.05, scope = PART, order = 23, report = 15)
    MOUNTING("mounting", ParsedQuery::mounting, PartFeatures::mounting) {
        @Override
        public Verdict conflict(MatchContext c) {
            String wanted = c.query().mounting();
            if (wanted == null) {
                return Verdict.MATCH;
            }
            String actual = c.part().mounting();
            if (actual == null || hybrid(c.part())) {
                return Verdict.UNKNOWN;
            }
            return wanted.equals(actual) ? Verdict.MATCH : Verdict.CONFLICT;
        }

        @Override
        public String mismatch(MatchContext c) {
            String wanted = c.query().mounting();
            String actual = c.part().mounting();
            return wanted != null && actual != null && !wanted.equals(actual) && !hybrid(c.part())
                    ? label() + ": " + actual + " instead of " + wanted : null;
        }
    },

    /**
     * The construction technology (thin film vs thick film, tantalum vs ceramic...) or the semiconductor of a transistor
     * or gate driver (GaN vs SiC vs silicon): same or compatible +, a different known one -, a known but not comparable
     * one earns nothing (and is unknown for the check).
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {RESISTOR, CAPACITOR, INDUCTOR, TRANSISTOR, DEFAULT})
    @Match(mode = COMPATIBLE, weight = 0.15, order = 15, report = 6)
    TECHNOLOGY("technology", ParsedQuery::technology, PartFeatures::technology) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return (double) c.compareTechnology((String) wanted, (String) actual);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            String wanted = c.query().technology();
            if (wanted == null) {
                return Verdict.MATCH;
            }
            int cmp = c.compareTechnology(wanted, c.part().technology());
            return cmp < 0 ? Verdict.CONFLICT : cmp == 0 ? Verdict.UNKNOWN : Verdict.MATCH;
        }
    },

    /**
     * The form factor class (chip, through-hole body, chassis, power package, power SMD) the request's words name, or
     * its package's class where the package is hard. Scored only when the words name it.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {RESISTOR, CAPACITOR, INDUCTOR, DEFAULT})
    @Match(mode = CUSTOM, weight = 0.10, order = 24, report = 16)
    FORM_FACTOR("form factor", ParsedQuery::formFactor, PartFeatures::formFactor) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            ParsedQuery q = c.query();
            if (q.formFactor() == null || !c.formFactorApplies(q.family())) {
                return Outcome.NOT_STATED;
            }
            Boolean same = c.compatibleFormFactor(c.requestedFormFactor(q, true), c.part().formFactor());
            return same == null ? Outcome.UNKNOWN : Outcome.counted(weight * grade(same), weight);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            ParsedQuery q = c.query();
            Boolean same = c.compatibleFormFactor(c.requestedFormFactor(q, c.isHard(PACKAGE)), c.part().formFactor());
            if (same == Boolean.FALSE) {
                return Verdict.CONFLICT;
            }
            return same == null && q.formFactor() != null ? Verdict.UNKNOWN : Verdict.MATCH;
        }

        @Override
        public String mismatch(MatchContext c) {
            String wanted = c.requestedFormFactor(c.query(), true);
            String actual = c.part().formFactor();
            return c.compatibleFormFactor(wanted, actual) == Boolean.FALSE
                    ? label() + ": " + c.formFactorLabel(actual) + " instead of " + c.formFactorLabel(wanted) : null;
        }
    },

    /**
     * An array or network: hard against an array for a single-element request (resistors, capacitors, ferrite beads);
     * a stated element count the part does not state is unverified. Checked and reported, not scored.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {RESISTOR, CAPACITOR, FERRITE, DEFAULT})
    @Match(mode = CUSTOM, weight = 0, order = 27, report = 18)
    ELEMENTS("elements", ParsedQuery::elements, PartFeatures::elements) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            return false;
        }

        @Override
        public Outcome score(MatchContext c, double weight) {
            Integer wanted = c.query().elements();
            Integer actual = c.part().elements();
            return wanted != null && wanted != ParsedQuery.ANY_ELEMENTS && actual != null
                    && actual == ParsedQuery.ANY_ELEMENTS ? Outcome.UNKNOWN : Outcome.NOT_STATED;
        }

        @Override
        public Verdict conflict(MatchContext c) {
            return singleWantedArrayFound(c) ? Verdict.CONFLICT : Verdict.MATCH;
        }

        @Override
        public String mismatch(MatchContext c) {
            Integer wanted = c.query().elements();
            Integer actual = c.part().elements();
            if (singleWantedArrayFound(c)) {
                return label() + ": " + c.elementsDisplay(actual) + " instead of single";
            }
            if (wanted == null) {
                return null;
            }
            String wantedDisplay = c.elementsDisplay(wanted);
            if (actual == null) {
                return label() + ": single instead of " + wantedDisplay;
            }
            return wanted != ParsedQuery.ANY_ELEMENTS && actual != ParsedQuery.ANY_ELEMENTS && !wanted.equals(actual)
                    ? label() + ": " + actual + " instead of " + wantedDisplay : null;
        }
    },

    /**
     * The fan type: axial or radial (a blower never answers an axial request, nor the reverse) and, when both sides
     * state it, AC or DC. A part that states neither is unverified.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = FAN)
    @Match(mode = CUSTOM, weight = 0.15, order = 30, report = 23)
    FAN_TYPE("fan type", q -> fanType(q.fan()), f -> fanType(f.fan())) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            ParsedQuery.Fan w = (ParsedQuery.Fan) wanted;
            ParsedQuery.Fan a = (ParsedQuery.Fan) actual;
            Boolean type = same(w.type(), a.type());
            Boolean supply = same(w.supply(), a.supply());
            if (type == Boolean.FALSE || supply == Boolean.FALSE) {
                return -1.0;
            }
            return type == null && supply == null ? null : 1.0;
        }

        @Override
        public String mismatch(MatchContext c) {
            Double g = compare(c);
            if (g == null || g >= 0) {
                return null;
            }
            return label() + ": " + fanTypeWords(c.part().fan()) + " instead of " + fanTypeWords(c.query().fan());
        }

        @Override
        public String describes(ParsedQuery q) {
            return wanted(q) == null ? null : fanTypeWords(q.fan());
        }
    },

    /**
     * The frame size of a fan: width and length within 0.5 mm, the depth when both state it ({@code 40x40x10mm} is no
     * {@code 40x40x20mm}; a bare {@code 40mm} fixes width and length only).
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = FAN)
    @Match(mode = CUSTOM, weight = 0.20, order = 31, report = 24)
    FRAME_SIZE("frame size", q -> q.fan() == null ? null : q.fan().frame(),
            f -> f.fan() == null ? null : f.fan().frame()) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return grade(((ParsedQuery.Frame) wanted).matches((ParsedQuery.Frame) actual));
        }

        @Override
        public String mismatch(MatchContext c) {
            Double g = compare(c);
            return g == null || g >= 0 ? null : label() + ": " + c.part().fan().frame().display() + " instead of "
                    + c.query().fan().frame().display();
        }

        @Override
        public String describes(ParsedQuery q) {
            return q.fan() == null || q.fan().frame() == null ? null : q.fan().frame().display();
        }
    },

    /**
     * The kind of LED: a plain request takes indicator and high power LEDs, never an addressable LED (WS2812, SK6812,
     * APA102) and never a part that is no discrete emitter (strip, laser, receiver, display, driver); an addressable
     * request takes addressable LEDs only. Every LED request states it (a plain emitter unless it names another).
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = LED)
    @Match(mode = CUSTOM, weight = 0.10, order = 38, report = 30)
    LED_TYPE("led type", q -> q.led() == null ? null : q.led().requestedType(),
            f -> f.led() == null ? null : f.led().type()) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return ParsedQuery.Led.typeGrade((String) wanted, (String) actual);
        }

        @Override
        public boolean namedInHint(ParsedQuery q) {
            return q.led() != null && q.led().type() != null;
        }

        @Override
        public String describes(ParsedQuery q) {
            return namedInHint(q) ? q.led().type() : null;
        }
    },

    /**
     * The colour of an LED: a hard type (a red request returns red LEDs only); {@code white} takes warm, neutral and
     * cool white, {@code green} yellow green, {@code yellow} and {@code amber} each other; RGB is a type of its own. A
     * part that states no colour is unverified.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = LED)
    @Match(mode = CUSTOM, weight = 0.20, order = 39, report = 31)
    COLOUR("colour", q -> q.led() == null ? null : q.led().colour(), f -> f.led() == null ? null : f.led().colour()) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return ParsedQuery.Led.colourGrade((String) wanted, (String) actual);
        }

        @Override
        public String describes(ParsedQuery q) {
            return (String) wanted(q);
        }
    },

    /** The wavelength of an LED within {@value #WAVELENGTH_TOLERANCE_NM} nm (dominant or peak, as the part states). */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = LED)
    @Match(mode = CUSTOM, weight = 0.10, order = 40, report = 32)
    WAVELENGTH("wavelength", ParsedQuery.WAVELENGTH) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return grade(Math.abs(number(actual) - number(wanted)) <= WAVELENGTH_TOLERANCE_NM + 1e-9);
        }
    },

    /** The USB type (Type-C vs Micro-B vs Type-A...); a part that is no USB connector is a mismatch. */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = PolicyFamily.USB)
    @Match(mode = CUSTOM, weight = 0.30, scope = USB, order = 1)
    USB_TYPE("usb type", ConstraintKind::usb, f -> null) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            return usb(q) != null && usb(q).usbType() != null;
        }

        @Override
        public Outcome score(MatchContext c, double weight) {
            String wanted = wantedUsbType(c);
            if (wanted == null) {
                return Outcome.NOT_STATED;
            }
            Connector actual = c.part().connector();
            if (actual == null) {
                return Outcome.UNKNOWN;
            }
            String actualType = usbType(c, actual);
            if (actualType != null) {
                return Outcome.counted(weight * grade(wanted.equals(actualType)), weight);
            }
            return otherConnector(actual) ? Outcome.counted(-weight, weight) : Outcome.UNKNOWN;
        }

        @Override
        public Verdict conflict(MatchContext c) {
            String wanted = wantedUsbType(c);
            Connector actual = c.part().connector();
            if (wanted == null || actual == null) {
                return Verdict.MATCH;
            }
            String actualType = usbType(c, actual);
            return actualType != null && !wanted.equals(actualType) || otherConnector(actual)
                    ? Verdict.CONFLICT : Verdict.MATCH;
        }
    },

    /**
     * The USB pin configuration, canonical on both sides (a 17P/18P Type-C part is a 16-pin one); half weight when the
     * request only implies it through its standard, and then never hard.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = PolicyFamily.USB)
    @Match(mode = CUSTOM, weight = 0.20, scope = USB, order = 2)
    PIN_CONFIGURATION("pin configuration", q -> usb(q) == null ? null : wantedPins(q), f -> null) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            Connector c = usb(q);
            return c != null && c.pinConfiguration() != null && !c.pinConfigurationImplied();
        }

        @Override
        public Outcome score(MatchContext c, double weight) {
            Connector wanted = usb(c.query());
            if (wanted == null || wanted.pinConfiguration() == null && wanted.positions() == null) {
                return Outcome.NOT_STATED;
            }
            Connector actual = c.part().connector();
            Integer actualPins = actual == null ? null : actualPins(c, actual);
            if (actualPins == null) {
                return Outcome.UNKNOWN;
            }
            double w = wanted.pinConfigurationImplied() ? weight / 2 : weight;
            return Outcome.counted(grade(wantedPins(c, wanted).equals(actualPins)) * w, w);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            Connector wanted = usb(c.query());
            Connector actual = c.part().connector();
            if (wanted == null || actual == null || wanted.pinConfigurationImplied()) {
                return Verdict.MATCH;
            }
            Integer wantedPins = wantedPins(c, wanted);
            Integer actualPins = actualPins(c, actual);
            return wantedPins != null && actualPins != null && !wantedPins.equals(actualPins)
                    ? Verdict.CONFLICT : Verdict.MATCH;
        }
    },

    /**
     * The USB standard (speed class): the same class +, a higher one half, a lower one or a power-only part -; a
     * higher standard satisfies a hard one.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = PolicyFamily.USB)
    @Match(mode = CUSTOM, weight = 0.20, scope = USB, order = 3)
    USB_STANDARD("usb standard", usbWanted(Connector::usbStandard), f -> null) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            String wanted = (String) wanted(c.query());
            if (wanted == null || !c.knownUsbStandard(wanted)) {
                return Outcome.NOT_STATED;
            }
            Connector actual = c.part().connector();
            if (actual == null) {
                return Outcome.UNKNOWN;
            }
            if (powerOnly(actual)) {
                return Outcome.counted(-weight, weight);
            }
            Double cmp = c.compareUsbStandards(wanted, actual.usbStandard());
            if (cmp == null) {
                return Outcome.UNKNOWN;
            }
            return Outcome.counted(cmp > 0 ? weight * cmp : -weight, weight);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            String wanted = (String) wanted(c.query());
            Connector actual = c.part().connector();
            if (wanted == null || actual == null || !c.knownUsbStandard(wanted)) {
                return Verdict.MATCH;
            }
            Double cmp = c.compareUsbStandards(wanted, actual.usbStandard());
            return powerOnly(actual) || cmp != null && cmp <= 0 ? Verdict.CONFLICT : Verdict.MATCH;
        }
    },

    /** The connector type (pin header, terminal block...); a generic type is scored but not counted. */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = PolicyFamily.CONNECTOR)
    @Match(mode = CUSTOM, weight = 0.10, scope = CONNECTOR, order = 7)
    CONNECTOR_TYPE("connector type", otherWanted(Connector::type), f -> null) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            Connector c = other(q);
            return c != null && c.type() != null && !ParsedQuery.CONNECTOR.equals(c.type());
        }

        @Override
        public Outcome score(MatchContext c, double weight) {
            Connector wanted = other(c.query());
            if (wanted == null) {
                return Outcome.NOT_STATED;
            }
            String type = wanted.type();
            boolean specific = type != null && !ParsedQuery.CONNECTOR.equals(type) && !ParsedQuery.HEADER.equals(type)
                    && !ParsedQuery.USB.equals(type);
            Connector actual = c.part().connector();
            Boolean same = actual == null ? null : c.connectorTypesMatch(type, actual.type());
            if (same == null) {
                return specific ? Outcome.UNKNOWN : Outcome.NOT_STATED;
            }
            double points = weight * grade(same);
            return specific ? Outcome.counted(points, weight) : Outcome.uncounted(points);
        }

        @Override
        public Verdict conflict(MatchContext c) {
            Connector wanted = other(c.query());
            Connector actual = c.part().connector();
            return wanted != null && actual != null
                    && c.connectorTypesMatch(wanted.type(), actual.type()) == Boolean.FALSE
                    ? Verdict.CONFLICT : Verdict.MATCH;
        }
    },

    /** The number of positions of a connector (pins, contacts, ways). */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = PolicyFamily.CONNECTOR)
    @Match(mode = EQUAL, weight = 0.30, scope = CONNECTOR, order = 1, report = 19)
    POSITIONS("positions", otherWanted(Connector::positions), partConnector(Connector::positions)),

    /** The contact pitch, within 0.03 mm (2.54 mm == 0.1"). */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = PolicyFamily.CONNECTOR)
    @Match(mode = CUSTOM, weight = 0.15, scope = CONNECTOR, order = 6, report = 21)
    PITCH("pitch", otherWanted(Connector::pitchMm), partConnector(Connector::pitchMm)) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return grade(Math.abs((Double) wanted - (Double) actual) <= PITCH_TOLERANCE_MM);
        }

        @Override
        public String mismatch(MatchContext c) {
            return super.mismatch(c) == null ? null : label() + ": " + c.part().connector().pitchDisplay()
                    + " instead of " + c.query().connector().pitchDisplay();
        }
    },

    /** Male or female; for USB connectors scored as {@link #USB_GENDER}. */
    @Relax(strategy = SOFT)
    @Relax(strategy = NEVER, families = {PolicyFamily.CONNECTOR, PolicyFamily.USB})
    @Match(mode = EQUAL, weight = 0.20, scope = CONNECTOR, order = 4, report = 20)
    GENDER("gender", anyWanted(Connector::gender), partConnector(Connector::gender)) {
        @Override
        public String mismatch(MatchContext c) {
            return other(c.query()) == null ? null : super.mismatch(c);
        }
    },

    /** Right angle or vertical; for USB connectors scored as {@link #USB_ORIENTATION}. */
    @Relax(strategy = LADDER, order = 3)
    @Match(mode = EQUAL, weight = 0.15, scope = CONNECTOR, order = 5, report = 22)
    ORIENTATION("orientation", anyWanted(Connector::orientation),
            partConnector(Connector::orientation)) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            return false;
        }

        @Override
        public String mismatch(MatchContext c) {
            return other(c.query()) == null ? null : super.mismatch(c);
        }
    },

    /** The ceramic dielectric (C0G == NP0). */
    @Relax(strategy = LADDER, order = 0)
    @Match(mode = EQUAL_IGNORE_CASE, weight = 0.15, order = 14, report = 5)
    DIELECTRIC("dielectric", ParsedQuery::dielectric, PartFeatures::dielectric) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            return false;
        }
    },

    /** The tolerance: the part's is at most the requested one. */
    @Relax(strategy = LADDER, order = 2)
    @Match(mode = CUSTOM, weight = 0.10, order = 26, report = 14)
    TOLERANCE("tolerance", ParsedQuery.TOLERANCE) {
        @Override
        public boolean namedInHint(ParsedQuery q) {
            return false;
        }

        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return grade(number(actual) <= number(wanted) + 1e-9);
        }
    },

    /** A TCR preference: a policy name only (not matched). */
    @Relax(strategy = LADDER, order = 4)
    TCR("tcr", q -> null, f -> null),

    /** An ESR preference: a policy name only (not matched). */
    @Relax(strategy = LADDER, order = 5)
    ESR("esr", q -> null, f -> null),

    /** A DCR preference: a policy name only; the maximum DCR is {@link #MAX_DCR}, "low DCR" is {@link #LOW_DCR}. */
    @Relax(strategy = LADDER, order = 6)
    DCR("dcr", q -> null, f -> null),

    /**
     * The speed of a fan, within 15 % (a fan twice as fast is another, louder product): outside it a mismatch, never an
     * exclusion; the ladder may loosen it for fans.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = LADDER, order = 7, families = FAN)
    @Match(mode = WITHIN, tolerance = 0.15, weight = 0.10, order = 32, report = 25)
    SPEED("speed", ParsedQuery.SPEED),

    /** The bearing of a fan (ball, sleeve, fluid dynamic...): a preference; a different one is a mismatch. */
    @Relax(strategy = SOFT)
    @Relax(strategy = LADDER, order = 8, families = FAN)
    @Match(mode = EQUAL, weight = 0.05, order = 33, report = 26)
    BEARING("bearing", q -> q.fan() == null ? null : q.fan().bearing(),
            f -> f.fan() == null ? null : f.fan().bearing()),

    /** The lens of an LED (clear, diffused, tinted): a preference the ladder may loosen for LEDs. */
    @Relax(strategy = SOFT)
    @Relax(strategy = LADDER, order = 9, families = LED)
    @Match(mode = EQUAL, weight = 0.05, order = 41, report = 33)
    LENS("lens", q -> q.led() == null ? null : q.led().lens(), f -> f.led() == null ? null : f.led().lens()),

    /**
     * The viewing angle of an LED within {@value #VIEWING_ANGLE_TOLERANCE_DEGREES} degrees: outside it a mismatch,
     * never an exclusion; the ladder may loosen it for LEDs.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = LADDER, order = 10, families = LED)
    @Match(mode = CUSTOM, weight = 0.05, order = 42, report = 34)
    VIEWING_ANGLE("viewing angle", ParsedQuery.VIEWING_ANGLE) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return grade(Math.abs(number(actual) - number(wanted)) <= VIEWING_ANGLE_TOLERANCE_DEGREES + 1e-9);
        }
    },

    /**
     * The colour temperature of a white LED within {@value #COLOUR_TEMPERATURE_TOLERANCE_K} K: outside it a mismatch,
     * never an exclusion; the ladder may loosen it for LEDs.
     */
    @Relax(strategy = SOFT)
    @Relax(strategy = LADDER, order = 11, families = LED)
    @Match(mode = CUSTOM, weight = 0.10, order = 43, report = 35)
    COLOUR_TEMPERATURE("colour temperature", ParsedQuery.COLOUR_TEMPERATURE) {
        @Override
        Double grade(MatchContext c, Object wanted, Object actual) {
            return grade(Math.abs(number(actual) - number(wanted)) <= COLOUR_TEMPERATURE_TOLERANCE_K + 1e-9);
        }
    },

    // ---------------------------------------------------------------- ratings (not in the policy table)

    /**
     * A minimum voltage rating (every family without an exact voltage). Far above the request it costs score
     * ({@link Overshoot}): above 2x the requested voltage, above 3x for capacitors (MLCC derating makes 2x to 3x normal
     * practice; user decision 2026-10-07).
     */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 16, report = 7)
    @Overshoot(ratio = 2.0)
    @Overshoot(ratio = 3.0, families = CAPACITOR)
    VOLTAGE_RATING("voltage", ParsedQuery.VOLTAGE),

    /** A minimum (rated) current (every family but fuses). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 17, report = 8)
    CURRENT("current", ParsedQuery.CURRENT),

    /** The current of a fuse, within 2 %. */
    @Relax(strategy = SOFT)
    @Match(mode = WITHIN, tolerance = 0.02, weight = 0.10, group = Match.RATING, order = 17, report = 8)
    EXACT_CURRENT("current", ParsedQuery.CURRENT, ComponentFamily.FUSE),

    /** The current a fan draws: a maximum (a fan drawing more than requested is below spec). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_MOST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 17, report = 8)
    MAX_CURRENT("current", ParsedQuery.CURRENT, ComponentFamily.FAN),

    /** A minimum saturation current (I_sat) of an inductor. */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 18, report = 9)
    SATURATION_CURRENT("saturation current", ParsedQuery.SATURATION_CURRENT),

    /** A minimum power rating. */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 19, report = 10)
    POWER("power", ParsedQuery.POWER),

    /** A minimum maximum operating temperature. */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 20, report = 11)
    TEMPERATURE("temperature", ParsedQuery.TEMPERATURE),

    /** A minimum rated lifetime. */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 21, report = 12)
    LIFETIME("lifetime", ParsedQuery.LIFETIME),

    /** A maximum DC resistance of an inductor or ferrite bead. */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_MOST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 22, report = 13)
    MAX_DCR("dcr", ParsedQuery.DCR),

    /** A minimum airflow of a fan (m³/h; CFM, m³/min and l/min are converted). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 34, report = 27)
    AIRFLOW("airflow", ParsedQuery.AIRFLOW),

    /** A minimum static pressure of a fan (Pa; mmH2O and inH2O are converted). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 35, report = 28)
    STATIC_PRESSURE("static pressure", ParsedQuery.STATIC_PRESSURE),

    /** A maximum noise of a fan (dBA). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_MOST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 36, report = 29)
    NOISE("noise", ParsedQuery.NOISE),

    /** The forward voltage of an LED: a request value is a maximum (an LED that needs more is below spec). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_MOST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 44, report = 36)
    FORWARD_VOLTAGE("forward voltage", ParsedQuery.FORWARD_VOLTAGE),

    /** A minimum luminous intensity of an LED (mcd, cd). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 45, report = 37)
    LUMINOUS_INTENSITY("luminous intensity", ParsedQuery.LUMINOUS_INTENSITY),

    /** A minimum luminous flux of an LED (lm). */
    @Relax(strategy = BELOW_SPEC)
    @Match(mode = AT_LEAST, tolerance = 1e-9, weight = 0.10, group = Match.RATING, order = 46, report = 38)
    LUMINOUS_FLUX("luminous flux", ParsedQuery.LUMINOUS_FLUX),

    // ---------------------------------------------------------------- preferences and connector-only signals

    /** "low DCR": a lower DC resistance ranks higher, {@code weight / (1 + DCR / 10 mOhm)} (score only). */
    @Relax(strategy = PREFERENCE)
    @Match(mode = CUSTOM, weight = 0.04, inGrade = false, order = 25)
    LOW_DCR(ParsedQuery.LOW_DCR, q -> q.prefers(ParsedQuery.LOW_DCR) ? Boolean.TRUE : null, f -> null) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Double dcr = c.part().value(ParsedQuery.DCR);
            return wanted(c.query()) != null && dcr != null && dcr >= 0
                    ? Outcome.counted(weight / (1 + dcr / LOW_DCR_REFERENCE_OHM), weight) : Outcome.NOT_STATED;
        }
    },

    /**
     * MOSFETs and transistors: a lower on-resistance (the part's {@code Resistance}, R_DS(on)) ranks higher,
     * {@code weight / (1 + R / 10 mOhm)} among parts that meet the request (score only, never in the match grade).
     */
    @Relax(strategy = PREFERENCE)
    @Match(mode = CUSTOM, weight = 0.04, inGrade = false, order = 29)
    LOW_RDS_ON("rds(on)", q -> ComponentFamily.has(q.family(), Trait.POLARISED) ? Boolean.TRUE : null, f -> null) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Double r = c.part().value(ParsedQuery.RESISTANCE);
            return wanted(c.query()) != null && r != null && r >= 0
                    ? Outcome.counted(weight / (1 + r / LOW_RDS_ON_REFERENCE_OHM), weight) : Outcome.NOT_STATED;
        }
    },

    /**
     * The features a fan request names (PWM, tacho, 4-wire, IP55...): the share of them the part states earns the
     * weight (score only; a part that states none earns nothing).
     */
    @Relax(strategy = PREFERENCE)
    @Match(mode = CUSTOM, weight = 0.04, inGrade = false, order = 37)
    FAN_FEATURES("fan features", q -> q.fan() == null || q.fan().features().isEmpty() ? null : q.fan().features(),
            f -> f.fan() == null || f.fan().features().isEmpty() ? null : f.fan().features()) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Object wanted = wanted(c.query());
            Object actual = actual(c.query(), c.part());
            if (wanted == null || actual == null) {
                return Outcome.NOT_STATED;
            }
            List<?> w = (List<?>) wanted;
            long found = w.stream().filter(((List<?>) actual)::contains).count();
            return Outcome.counted(weight * found / w.size(), weight);
        }
    },

    /**
     * The orientation of a part that is no connector: an LED's right angle (side view), reverse mount or vertical (top
     * view). A preference: a different one is a mismatch, never an exclusion.
     */
    @Relax(strategy = SOFT)
    @Match(mode = EQUAL, weight = 0.05, scope = PART, order = 47, report = 39)
    PART_ORIENTATION("orientation", ConstraintKind::partOrientation, ConstraintKind::partOrientation),

    /** Rows of a connector: a different row count costs the weight, the same earns nothing. */
    @Relax(strategy = SOFT)
    @Match(mode = CUSTOM, weight = 0.10, scope = CONNECTOR, order = 2)
    ROWS("rows", otherWanted(Connector::rows), partConnector(Connector::rows)) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Object wanted = wanted(c.query());
            Object actual = actual(c.query(), c.part());
            return wanted == null || actual == null ? Outcome.NOT_STATED
                    : Outcome.uncounted(wanted.equals(actual) ? 0 : -weight);
        }
    },

    /** A header request with positions but no rows prefers single-row parts: a multi-row one costs the weight. */
    @Relax(strategy = SOFT)
    @Match(mode = CUSTOM, weight = 0.08, scope = CONNECTOR, order = 3)
    SINGLE_ROW("rows", q -> null, f -> null) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Connector wanted = other(c.query());
            Connector actual = c.part().connector();
            return wanted != null && actual != null && wanted.rows() == null && wanted.positions() != null
                    && actual.rows() != null && actual.rows() > 1 && c.isHeader(wanted.type())
                    ? Outcome.uncounted(-weight) : Outcome.NOT_STATED;
        }
    },

    /** SMD or THT of a (non-USB) connector request; a part without connector details earns nothing. */
    @Relax(strategy = SOFT)
    @Match(mode = EQUAL, weight = 0.05, scope = CONNECTOR, order = 8)
    CONNECTOR_MOUNTING("mounting", ParsedQuery::mounting, PartFeatures::mounting) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Outcome o = super.score(c, weight);
            return o.state() == Outcome.State.COUNTED && c.part().connector() == null
                    ? Outcome.counted(0, weight) : o;
        }
    },

    /** Gender of a USB connector. */
    @Relax(strategy = SOFT)
    @Match(mode = EQUAL, weight = 0.15, scope = USB, order = 4)
    USB_GENDER("gender", usbWanted(Connector::gender), partConnector(Connector::gender)),

    /**
     * Mounting of a USB connector: a requested mid-mount / hybrid / top-mount style against the part's style, else
     * SMD/THT, where a hybrid part counts half.
     */
    @Relax(strategy = SOFT)
    @Match(mode = CUSTOM, weight = 0.10, scope = USB, order = 5)
    USB_MOUNTING("mounting", q -> null, f -> null) {
        @Override
        public Outcome score(MatchContext c, double weight) {
            Connector wanted = usb(c.query());
            if (wanted == null || wanted.mountingStyle() == null && c.query().mounting() == null) {
                return Outcome.NOT_STATED;
            }
            Connector actual = c.part().connector();
            Double m = actual == null ? null
                    : usbMounting(wanted.mountingStyle(), c.query().mounting(), actual, c.part().mounting());
            return m == null ? Outcome.UNKNOWN : Outcome.counted(weight * m, weight);
        }
    },

    /** Orientation of a USB connector. */
    @Relax(strategy = SOFT)
    @Match(mode = EQUAL, weight = 0.05, scope = USB, order = 6)
    USB_ORIENTATION("orientation", usbWanted(Connector::orientation),
            partConnector(Connector::orientation)),

    /** A requested waterproof USB connector: earned when the part is. */
    @Relax(strategy = SOFT)
    @Match(mode = FEATURE, weight = 0.03, scope = USB, order = 7)
    WATERPROOF(ParsedQuery.WATERPROOF),

    /** A requested board lock: earned when the part has one. */
    @Relax(strategy = SOFT)
    @Match(mode = FEATURE, weight = 0.03, scope = USB, order = 8)
    BOARD_LOCK(ParsedQuery.BOARD_LOCK),

    /** A requested power-only USB connector: earned when the part is one. */
    @Relax(strategy = SOFT)
    @Match(mode = FEATURE, weight = 0.03, scope = USB, order = 9)
    POWER_ONLY(ParsedQuery.POWER_ONLY);

    /** Largest difference in nanometres between two wavelengths that are the same. */
    public static final double WAVELENGTH_TOLERANCE_NM = 10;
    /** Largest difference in degrees between two viewing angles that are the same. */
    public static final double VIEWING_ANGLE_TOLERANCE_DEGREES = 15;
    /** Largest difference in kelvin between two colour temperatures that are the same. */
    public static final double COLOUR_TEMPERATURE_TOLERANCE_K = 300;
    /** Absolute tolerance for "same pitch" in millimetres (2.54 == 0.1" == 2.540). */
    public static final double PITCH_TOLERANCE_MM = 0.03;
    /** DC resistance at which the {@link #LOW_DCR} preference is half its weight. */
    static final double LOW_DCR_REFERENCE_OHM = 0.01;
    /** On-resistance at which the {@link #LOW_RDS_ON} preference is half its weight. */
    static final double LOW_RDS_ON_REFERENCE_OHM = 0.01;
    private static final List<String> PRIMARY_KINDS = List.of(ParsedQuery.CAPACITANCE, ParsedQuery.RESISTANCE,
            ParsedQuery.INDUCTANCE, ParsedQuery.IMPEDANCE);

    private final String label;
    /** The {@link ParsedQuery} constraint kind of a numeric attribute, else null. */
    private final String measure;
    /** The family labels an exact rating applies to; empty for every family no exact rating of the measure claims. */
    private final Set<String> onlyFor;
    private final Function<ParsedQuery, Object> wanted;
    private final Function<PartFeatures, Object> actual;
    // read from the annotations (static initialiser)
    private List<Relax> relax;
    private Match match;
    private List<Overshoot> overshoot;

    ConstraintKind(String label, Function<ParsedQuery, Object> wanted, Function<PartFeatures, Object> actual) {
        this.label = label;
        this.measure = null;
        this.onlyFor = Set.of();
        this.wanted = wanted;
        this.actual = actual;
    }

    /** A USB feature ({@link ParsedQuery#WATERPROOF}...): wanted when the request names it, the part has it or not. */
    ConstraintKind(String usbFeature) {
        this(usbFeature, q -> usb(q) != null && usb(q).hasFeature(usbFeature) ? Boolean.TRUE : null,
                f -> f.connector() != null && f.connector().hasFeature(usbFeature));
    }

    /** A numeric constraint of {@code measure}; with {@code onlyFor}, an exact rating of those families. */
    ConstraintKind(String label, String measure, ComponentFamily... onlyFor) {
        this.label = label;
        this.measure = measure;
        this.onlyFor = Arrays.stream(onlyFor).map(ComponentFamily::label).collect(Collectors.toUnmodifiableSet());
        this.wanted = null;
        this.actual = null;
    }

    // ---------------------------------------------------------------- declarations

    private static final List<ConstraintKind> POLICY_KINDS;
    private static final List<ConstraintKind> SCORED;
    private static final List<ConstraintKind> REPORTED;
    private static final List<ConstraintKind> LADDER_KINDS;
    private static final List<String> RATING_MEASURES;
    private static final Map<String, ConstraintKind> BY_POLICY_NAME = new HashMap<>();
    /** The families with a rule of their own for a measure ({@code voltage} -&gt; regulator, zener, fan). */
    private static final Map<String, Set<String>> VARIANT_FAMILIES = new HashMap<>();
    /** The families whose rule of a measure is exact (not a minimum or maximum), by measure. */
    private static final Map<String, Set<String>> EXACT_FAMILIES = new HashMap<>();

    static {
        for (ConstraintKind k : values()) {
            Field field;
            try {
                field = ConstraintKind.class.getField(k.name());
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
            k.relax = List.of(field.getAnnotationsByType(Relax.class));
            k.match = field.getAnnotation(Match.class);
            k.overshoot = List.of(field.getAnnotationsByType(Overshoot.class));
            k.validate();
        }
        List<ConstraintKind> policy = new ArrayList<>();
        List<ConstraintKind> scored = new ArrayList<>();
        List<ConstraintKind> reported = new ArrayList<>();
        List<ConstraintKind> ladder = new ArrayList<>();
        for (ConstraintKind k : values()) {
            if (k.isPolicyKind()) {
                policy.add(k);
                if (BY_POLICY_NAME.put(k.label, k) != null) {
                    throw new IllegalStateException("two policy kinds named " + k.label);
                }
            }
            if (k.match != null) {
                scored.add(k);
                if (k.match.report() > 0) {
                    reported.add(k);
                }
            }
            if (k.relax.stream().anyMatch(r -> r.strategy() == LADDER)) {
                ladder.add(k);
            }
            if (!k.onlyFor.isEmpty()) {
                VARIANT_FAMILIES.computeIfAbsent(k.measure, m -> new LinkedHashSet<>()).addAll(k.onlyFor);
                if (k.match.mode() != AT_LEAST && k.match.mode() != AT_MOST) {
                    EXACT_FAMILIES.computeIfAbsent(k.measure, m -> new LinkedHashSet<>()).addAll(k.onlyFor);
                }
            }
        }
        scored.sort(Comparator.comparingInt(k -> k.match.order()));
        reported.sort(Comparator.comparingInt(k -> k.match.report()));
        ladder.sort(Comparator.comparingInt(ConstraintKind::ladderOrder));
        POLICY_KINDS = List.copyOf(policy);
        SCORED = List.copyOf(scored);
        REPORTED = List.copyOf(reported);
        LADDER_KINDS = List.copyOf(ladder);
        Set<String> ratings = new LinkedHashSet<>();
        SCORED.stream().filter(ConstraintKind::isRating).forEach(k -> ratings.add(k.measure));
        RATING_MEASURES = List.copyOf(ratings);
    }

    private void validate() {
        long general = relax.stream().filter(ConstraintKind::isGeneral).count();
        if (general != 1) {
            throw new IllegalStateException(this + " needs exactly one general @Relax, has " + general);
        }
        if (generalStrategy() == NEVER) {
            throw new IllegalStateException(this + ": the general @Relax is the strategy when a family does not make "
                    + "the kind hard; NEVER belongs to family-specific declarations");
        }
        if (relax.stream().filter(r -> r.strategy() == LADDER).count() > 1) {
            throw new IllegalStateException(this + ": one LADDER declaration (its order is the ladder position)");
        }
        if (match != null && !match.group().isEmpty() && measure == null) {
            throw new IllegalStateException(this + ": a group member needs a measure");
        }
        if (!overshoot.isEmpty() && (match == null || match.mode() != AT_LEAST
                || overshoot.stream().filter(o -> o.families().length == 0).count() != 1)) {
            throw new IllegalStateException(this + ": @Overshoot needs an AT_LEAST rating and one general declaration");
        }
    }

    /** The wire and configuration name ({@code package}, {@code usb type}, {@code voltage}...). */
    public String label() {
        return label;
    }

    /** The {@link ParsedQuery} constraint kind of a numeric attribute ({@code saturation_current}), else null. */
    public String measure() {
        return measure;
    }

    /** The matching rule, null for a kind that is a policy name only ({@link #TCR}, {@link #ESR}, {@link #DCR}). */
    public Match match() {
        return match;
    }

    /** The score weight of the kind ({@link Match#weight()}), 0 when it is not matched. */
    public double weight() {
        return match == null ? 0 : match.weight();
    }

    /** The relaxation declarations, the general one and the family-specific ones. */
    public List<Relax> relaxDeclarations() {
        return relax;
    }

    /** The strategy when no family makes the kind hard (the general {@link Relax}). */
    public RelaxStrategy generalStrategy() {
        return general().strategy();
    }

    /**
     * The declared strategy for a policy family: its own declaration, then the {@link Relax#allFamilies()} one, then
     * the general one.
     */
    public RelaxStrategy strategy(PolicyFamily family) {
        return declaration(family).strategy();
    }

    /**
     * The strategy for a policy family when it does not make the kind hard: its own declaration unless that is
     * {@link RelaxStrategy#NEVER} (the speed of a fan is {@link RelaxStrategy#LADDER}), else the general one.
     */
    public RelaxStrategy relaxedStrategy(PolicyFamily family) {
        RelaxStrategy declared = strategy(family);
        return declared == NEVER ? generalStrategy() : declared;
    }

    /** The ladder position of a {@link RelaxStrategy#LADDER} kind. */
    public int ladderOrder() {
        return relax.stream().filter(r -> r.strategy() == LADDER).mapToInt(Relax::order).findFirst().orElse(0);
    }

    /** The declared cost of a relaxed mismatch for a policy family, -1 for the {@link Match#weight()}. */
    public double cost(PolicyFamily family) {
        return declaration(family).cost();
    }

    private Relax general() {
        return relax.stream().filter(ConstraintKind::isGeneral).findFirst().orElseThrow();
    }

    private static boolean isGeneral(Relax r) {
        return r.families().length == 0 && !r.allFamilies();
    }

    private Relax declaration(PolicyFamily family) {
        return relax.stream().filter(r -> List.of(r.families()).contains(family)).findFirst()
                .or(() -> relax.stream().filter(Relax::allFamilies).findFirst())
                .orElseGet(this::general);
    }

    /**
     * The {@link Overshoot} declaration for a policy family (its own, else the general one), null when the kind
     * declares none.
     */
    public Overshoot overshoot(PolicyFamily family) {
        return overshoot.stream().filter(o -> List.of(o.families()).contains(family)).findFirst()
                .or(() -> overshoot.stream().filter(o -> o.families().length == 0).findFirst())
                .orElse(null);
    }

    /**
     * The overshoot penalty of a part rated {@code actual} for a request of {@code wanted} in a policy family: 0 up to
     * the declared ratio, then {@link Overshoot#perOctave()} per octave above it, up to {@link Overshoot#maxOctaves()}.
     */
    public double overshootPenalty(PolicyFamily family, double wanted, double actual) {
        Overshoot o = overshoot(family);
        if (o == null || wanted <= 0 || actual <= wanted * o.ratio()) {
            return 0;
        }
        double octaves = Math.log(actual / (wanted * o.ratio())) / Math.log(2);
        return o.perOctave() * Math.min(o.maxOctaves(), octaves);
    }

    /** True for kinds a family can make hard or the ladder can loosen: the names of the policy table. */
    public boolean isPolicyKind() {
        return relax.stream().anyMatch(r -> r.strategy() == NEVER || r.strategy() == LADDER);
    }

    /** True for the members of the {@link Match#RATING} group. */
    public boolean isRating() {
        return match != null && Match.RATING.equals(match.group());
    }

    /** The policy kinds in check order. */
    public static List<ConstraintKind> policyKinds() {
        return POLICY_KINDS;
    }

    /** The policy kind of a name, null when none has it. */
    public static ConstraintKind byPolicyName(String name) {
        return BY_POLICY_NAME.get(name);
    }

    /** The matched kinds in score order. */
    public static List<ConstraintKind> scored() {
        return SCORED;
    }

    /** The kinds that report mismatches, in report order. */
    public static List<ConstraintKind> reported() {
        return REPORTED;
    }

    /** The kinds with a {@link RelaxStrategy#LADDER} declaration (general or for a family), in ladder order. */
    public static List<ConstraintKind> ladder() {
        return LADDER_KINDS;
    }

    /** The {@link ParsedQuery} kinds of the ratings, in score order (voltage, current, ..., dcr). */
    public static List<String> ratingMeasures() {
        return RATING_MEASURES;
    }

    // ---------------------------------------------------------------- matching

    /** The stated value of the request (a {@link ParsedQuery.Constraint}, a string, a number), null when not stated. */
    public Object wanted(ParsedQuery q) {
        if (measure == null) {
            return wanted.apply(q);
        }
        ParsedQuery.Constraint c = q.constraint(measure);
        if (c == null) {
            return null;
        }
        return onlyFor.isEmpty() ? (hasVariant(measure, q.family()) ? null : c)
                : q.family() != null && onlyFor.contains(q.family()) ? c : null;
    }

    /** The part's value ({@link PartFeatures.Measure}, a string, a number), null when the part does not state it. */
    public Object actual(ParsedQuery q, PartFeatures f) {
        return measure != null ? f.measure(measure) : actual.apply(f);
    }

    /** The name reported for the kind (conflicts, unverified, mismatches); the primary value by its kind. */
    public String reported(ParsedQuery q) {
        return label;
    }

    /** True when a hint names the kind as a stated hard constraint. */
    public boolean namedInHint(ParsedQuery q) {
        return wanted(q) != null;
    }

    /**
     * The words a hint describes the request with for this kind ({@code DC axial}, {@code 40x40x10mm}), null for the
     * kinds the description names otherwise (value, polarity, package...).
     */
    public String describes(ParsedQuery q) {
        return null;
    }

    /** The comparison of the stated and the known value: 1 match, -1 miss, between for partial, null not comparable. */
    public Double compare(MatchContext c) {
        Object w = wanted(c.query());
        Object a = actual(c.query(), c.part());
        return w == null || a == null ? null : grade(c, w, a);
    }

    /** The comparison of two known values by {@link Match#mode()}; {@link MatchMode#CUSTOM} kinds override it. */
    Double grade(MatchContext c, Object wanted, Object actual) {
        double tolerance = match.tolerance();
        return switch (match.mode()) {
            case EQUAL -> grade(wanted.equals(actual));
            case EQUAL_IGNORE_CASE -> grade(((String) wanted).equalsIgnoreCase((String) actual));
            case AT_LEAST -> grade(number(actual) >= number(wanted) * (1 - tolerance));
            case AT_MOST -> grade(number(actual) <= number(wanted) * (1 + tolerance));
            case WITHIN -> grade(sameValue(number(wanted), number(actual), tolerance));
            case FEATURE -> (Boolean) actual ? 1.0 : 0.0;
            case COMPATIBLE, CUSTOM -> throw new IllegalStateException(this + " declares its own comparison");
        };
    }

    /**
     * The kind's contribution to the score at {@code weight}: not stated, unknown (unverified), or the points and the
     * weight a part matching it would earn.
     */
    public Outcome score(MatchContext c, double weight) {
        Object w = wanted(c.query());
        if (w == null) {
            return Outcome.NOT_STATED;
        }
        Object a = actual(c.query(), c.part());
        Double g = a == null ? null : grade(c, w, a);
        return g == null ? Outcome.UNKNOWN : Outcome.counted(weight * g, weight);
    }

    /** The hard-constraint check of the kind: a known contradiction, unknown, or nothing against the part. */
    public Verdict conflict(MatchContext c) {
        Double g = compare(c);
        return g != null && g < 0 ? Verdict.CONFLICT : Verdict.MATCH;
    }

    /** The mismatch in plain words ({@code "package: 1210 instead of 1206"}), null when there is none. */
    public String mismatch(MatchContext c) {
        Double g = compare(c);
        if (g == null || g >= 0) {
            return null;
        }
        String relation = match.mode() == AT_LEAST ? " below " : match.mode() == AT_MOST ? " above " : " instead of ";
        return reported(c.query()) + ": " + display(actual(c.query(), c.part())) + relation
                + display(wanted(c.query()));
    }

    /** The check verdict of one kind. */
    public enum Verdict { MATCH, UNKNOWN, CONFLICT }

    /**
     * A kind's contribution to the score.
     *
     * @param state  not stated, unknown (unverified), counted (in what a part can earn) or uncounted (the points only)
     * @param points the score points (signed)
     * @param weight what a part matching it would earn (counted only)
     */
    public record Outcome(State state, double points, double weight) {

        public enum State { NOT_STATED, UNKNOWN, COUNTED, UNCOUNTED }

        public static final Outcome NOT_STATED = new Outcome(State.NOT_STATED, 0, 0);
        public static final Outcome UNKNOWN = new Outcome(State.UNKNOWN, 0, 0);

        public static Outcome counted(double points, double weight) {
            return new Outcome(State.COUNTED, points, weight);
        }

        public static Outcome uncounted(double points) {
            return new Outcome(State.UNCOUNTED, points, 0);
        }
    }

    // ---------------------------------------------------------------- shared rules

    /**
     * The primary value of a request: the frequency of a crystal or oscillator (a capacitance there is the load
     * capacitance), else the first of capacitance, resistance, inductance and impedance; the frequency also when the
     * family is not known. Null when the request states none.
     */
    public static String primaryKind(ParsedQuery query) {
        String family = query.family();
        boolean frequencyFamily = ComponentFamily.has(family, Trait.FREQUENCY_VALUED);
        if (!frequencyFamily) {
            for (String kind : PRIMARY_KINDS) {
                if (query.constraint(kind) != null) {
                    return kind;
                }
            }
        }
        return query.constraint(ParsedQuery.FREQUENCY) != null && (family == null || frequencyFamily)
                ? ParsedQuery.FREQUENCY : null;
    }

    /** True when the request is a crystal with a capacitance: its load capacitance. */
    public static boolean isLoadCapacitance(ParsedQuery query) {
        return ComponentFamily.CRYSTAL.label().equals(query.family())
                && query.constraint(ParsedQuery.CAPACITANCE) != null;
    }

    /**
     * True when a rating of {@code kind} must match rather than be exceeded: the output voltage of a regulator, the
     * Zener voltage, the supply voltage of a fan, the current of a fuse (the families of the exact rating kinds).
     */
    public static boolean isExactRating(String kind, String family) {
        return family != null && EXACT_FAMILIES.getOrDefault(kind, Set.of()).contains(family);
    }

    /**
     * True when a rating of {@code kind} is a minimum for {@code family} (a part rated higher satisfies it): the
     * general rule of the measure is a minimum and the family has no rule of its own (a fan's current is a maximum).
     */
    public static boolean isMinimumRating(String kind, String family) {
        if (hasVariant(kind, family)) {
            return false;
        }
        return SCORED.stream().anyMatch(k -> kind.equals(k.measure) && k.onlyFor.isEmpty() && k.isRating()
                && k.match.mode() == AT_LEAST);
    }

    /** True when {@code family} has a rule of its own for the measure {@code kind} ({@link #EXACT_VOLTAGE}...). */
    private static boolean hasVariant(String kind, String family) {
        return family != null && VARIANT_FAMILIES.getOrDefault(kind, Set.of()).contains(family);
    }

    /** True when {@code actual} is within {@code relativeTolerance} of {@code wanted}. */
    public static boolean sameValue(double wanted, double actual, double relativeTolerance) {
        if (wanted == 0) {
            return Math.abs(actual) < 1e-12;
        }
        return Math.abs(actual - wanted) <= relativeTolerance * Math.abs(wanted) + 1e-15;
    }

    /**
     * The exact voltage of a Zener diode or a fixed regulator: true when one of the voltages the part states as its
     * specification ({@link PartFeatures#voltages()}, else its voltage) is the requested one, false when it states
     * voltages and none is, null when the request has no exact voltage or the part states none.
     */
    public static Boolean exactVoltage(MatchContext c) {
        ParsedQuery.Constraint wanted = (ParsedQuery.Constraint) EXACT_VOLTAGE.wanted(c.query());
        if (wanted == null) {
            return null;
        }
        PartFeatures f = c.part();
        Double voltage = f.value(ParsedQuery.VOLTAGE);
        List<Double> candidates = !f.voltages().isEmpty() ? f.voltages()
                : voltage != null ? List.of(voltage) : List.of();
        if (candidates.isEmpty()) {
            return null;
        }
        double tolerance = EXACT_VOLTAGE.match.tolerance();
        return candidates.stream().anyMatch(v -> sameValue(wanted.value(), v, tolerance));
    }

    /**
     * Mounting comparison in [-1, 1], null when unknown: a requested mid-mount / hybrid / top-mount style against the
     * part's style (a part that does not say is unknown); else SMD/THT against the part's mounting, where a hybrid part
     * (SMD signal pins, through-hole shell legs) counts half for either.
     */
    public static Double usbMounting(String wantedStyle, String wantedMounting, Connector actual,
                                     String partMounting) {
        String actualStyle = actual.mountingStyle();
        if (wantedStyle != null && actualStyle != null) {
            return wantedStyle.equals(actualStyle) ? 1.0 : -1.0;
        }
        if (wantedStyle != null && ParsedQuery.HYBRID.equals(wantedStyle)
                && actual.hasFeature(ParsedQuery.FULLY_SMD)) {
            return -1.0;
        }
        if (wantedMounting == null) {
            return null;
        }
        if (ParsedQuery.HYBRID.equals(actualStyle)) {
            return 0.5;
        }
        if (partMounting == null) {
            return null;
        }
        return wantedMounting.equals(partMounting) ? 1.0 : -1.0;
    }

    static double grade(boolean match) {
        return match ? 1.0 : -1.0;
    }

    private static double number(Object value) {
        if (value instanceof ParsedQuery.Constraint c) {
            return c.value();
        }
        if (value instanceof PartFeatures.Measure m) {
            return m.value();
        }
        return ((Number) value).doubleValue();
    }

    private static String display(Object value) {
        if (value instanceof ParsedQuery.Constraint c) {
            return c.display();
        }
        if (value instanceof PartFeatures.Measure m) {
            return m.display();
        }
        return String.valueOf(value);
    }

    /** The family signal: the same (or a more specific) family 1, the generic family 0, another known family -1. */
    private static double familyGrade(MatchContext c) {
        String wanted = c.query().family();
        String actual = c.part().family();
        if (actual != null) {
            if (actual.equals(wanted) || wanted.equals(ComponentFamily.parentOf(actual))) {
                return 1;
            }
            if (actual.equals(ComponentFamily.parentOf(wanted))) {
                return 0;   // generic part family, e.g. "diode" for a "schottky" request: neutral
            }
            return -1;
        }
        for (String word : c.familyWords(wanted)) {
            if (c.part().text().contains(word)) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * The component type: known families that are not the same or a specialisation of one another (a crystal is no
     * oscillator, a Zener no TVS diode), a standard rectifier or switching diode against a Schottky, Zener, TVS or LED
     * (either way), a fixed against an adjustable regulator.
     */
    private static boolean typeConflict(MatchContext c) {
        ParsedQuery q = c.query();
        PartFeatures f = c.part();
        String wanted = q.family();
        String actual = f.family();
        if (!ComponentFamily.compatible(wanted, actual)) {
            return true;
        }
        String diode = ComponentFamily.DIODE.label();
        if (diode.equals(wanted) && ParsedQuery.STANDARD.equals(q.subtype()) && specialisedDiode(actual)) {
            return true;
        }
        if (specialisedDiode(wanted) && diode.equals(actual) && ParsedQuery.STANDARD.equals(f.subtype())) {
            return true;
        }
        return ComponentFamily.REGULATOR.label().equals(wanted) && q.subtype() != null && f.subtype() != null
                && !q.subtype().equals(f.subtype());
    }

    /** The orientation of an LED request (null for every other request). */
    private static Object partOrientation(ParsedQuery q) {
        return q.led() == null ? null : q.led().orientation();
    }

    /** The orientation of an LED part (null for every other part). */
    private static Object partOrientation(PartFeatures f) {
        return f.led() == null ? null : f.led().orientation();
    }

    /** The fan attributes of a request or part when they state the type or the supply, else null. */
    private static ParsedQuery.Fan fanType(ParsedQuery.Fan fan) {
        return fan == null || fan.type() == null && fan.supply() == null ? null : fan;
    }

    /** {@code DC axial}, {@code radial}, {@code AC}: the supply and the type words. */
    private static String fanTypeWords(ParsedQuery.Fan fan) {
        return ((fan.supply() == null ? "" : fan.supply()) + " " + (fan.type() == null ? "" : fan.type())).strip();
    }

    /** Equal, different, or null when either is unknown. */
    private static Boolean same(String wanted, String actual) {
        return wanted == null || actual == null ? null : wanted.equals(actual);
    }

    /** A Schottky, Zener, TVS or LED family (a standard rectifier is none of them). */
    private static boolean specialisedDiode(String family) {
        return ComponentFamily.DIODE.label().equals(ComponentFamily.parentOf(family));
    }

    private static boolean hybrid(PartFeatures f) {
        return f.connector() != null && ParsedQuery.HYBRID.equals(f.connector().mountingStyle());
    }

    /** A single-element request (resistors, capacitors, ferrite beads) against an array or network. */
    private static boolean singleWantedArrayFound(MatchContext c) {
        ParsedQuery q = c.query();
        return q.elements() == null && c.part().elements() != null && ComponentFamily.has(q.family(), Trait.ARRAYS);
    }

    /** The connector attributes of a USB request, else null. */
    private static Connector usb(ParsedQuery q) {
        return q.connector() != null && q.connector().isUsb() ? q.connector() : null;
    }

    /** The connector attributes of a request for another connector, else null. */
    private static Connector other(ParsedQuery q) {
        return q.connector() != null && !q.connector().isUsb() ? q.connector() : null;
    }

    /** The USB type of a USB request: as stated, else implied by its connector type; null when neither says. */
    private static String wantedUsbType(MatchContext c) {
        Connector wanted = usb(c.query());
        return wanted == null ? null : usbType(c, wanted);
    }

    /** The stated pin configuration or positions of a USB request (for statedness; compared canonically). */
    private static Integer wantedPins(ParsedQuery q) {
        Connector c = usb(q);
        return c.pinConfiguration() != null ? c.pinConfiguration() : c.positions();
    }

    private static String usbType(MatchContext c, Connector connector) {
        return connector.usbType() != null ? connector.usbType() : c.usbTypeOf(connector.type());
    }

    /** The canonical pin configuration of a USB request. */
    private static Integer wantedPins(MatchContext c, Connector wanted) {
        Integer pins = wanted.pinConfiguration() != null ? wanted.pinConfiguration()
                : c.usbConfiguration(usbType(c, wanted), wanted.positions());
        return pins != null ? pins : wanted.positions();
    }

    /** The canonical pin configuration of a USB part, read with the request's USB type. */
    private static Integer actualPins(MatchContext c, Connector actual) {
        Connector wanted = usb(c.query());
        return actual.pinConfiguration() != null ? actual.pinConfiguration()
                : c.usbConfiguration(usbType(c, wanted), actual.positions());
    }

    /** A part that is a connector of another kind (a pin header, an RJ45 jack). */
    private static boolean otherConnector(Connector actual) {
        return !actual.isUsb() && actual.type() != null && !ParsedQuery.CONNECTOR.equals(actual.type());
    }

    private static boolean powerOnly(Connector actual) {
        return actual.hasFeature(ParsedQuery.POWER_ONLY) && actual.usbStandard() == null;
    }

    /** An attribute of a USB request ({@code null} for any other request). */
    private static Function<ParsedQuery, Object> usbWanted(Function<Connector, Object> attribute) {
        return q -> usb(q) == null ? null : attribute.apply(usb(q));
    }

    /** An attribute of a request for a connector other than USB ({@code null} for any other request). */
    private static Function<ParsedQuery, Object> otherWanted(Function<Connector, Object> attribute) {
        return q -> other(q) == null ? null : attribute.apply(other(q));
    }

    /** An attribute of any connector request ({@code null} for a part request). */
    private static Function<ParsedQuery, Object> anyWanted(Function<Connector, Object> attribute) {
        return q -> q.connector() == null ? null : attribute.apply(q.connector());
    }

    /** An attribute of the part's connector details ({@code null} when it has none). */
    private static Function<PartFeatures, Object> partConnector(Function<Connector, Object> attribute) {
        return f -> f.connector() == null ? null : attribute.apply(f.connector());
    }
}
