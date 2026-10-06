package ro.alacrity.kina.search;

import lombok.extern.slf4j.Slf4j;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ParsedQuery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which stated constraints are hard (never relaxed: a part whose known value contradicts one is excluded and counted in
 * {@code excluded_by_constraints}) and which are relaxable (the relaxation ladder may loosen them, a part that misses
 * one is returned with the miss in {@code mismatches} and the constraint in {@code constraints_relaxed}), per component
 * family (DESIGN.md 3.4 "Hard constraints", user decision 2026-10-07). One place for the whole table: the ranker's
 * exclusion check ({@link #check}), the ladder ({@link DistributorPhraser#ladder}) and the reporting use it.
 *
 * <p>Code defaults ({@link #DEFAULT_HARD}): the primary value (resistance, capacitance, inductance, the impedance of a
 * ferrite bead at its frequency, the frequency of a crystal or oscillator), mounting, technology and the package are
 * hard for every family, except that the package of inductors, crystals and oscillators is relaxable. The form
 * factor ({@link FormFactor}: chip, through-hole body, chassis, power package, power SMD) is hard for resistors,
 * capacitors, inductors and the default family: a part of another class is excluded, one whose class cannot be read
 * stays unverified. The component
 * type is hard (crystal vs oscillator, Schottky vs standard rectifier vs Zener vs TVS, fixed vs adjustable regulator),
 * as are the transistor polarity, the exact voltages (Zener voltage, regulator output voltage), the load capacitance of
 * a crystal, and for connectors the type, gender, positions and pitch; for USB connectors the USB type, the stated pin
 * configuration and the standard (which may be exceeded). Relaxable: dielectric, tolerance, connector orientation,
 * TCR, ESR and DCR preferences. Ratings (minimum voltage, current, power, temperature, lifetime; maximum DCR) are not
 * in this table: they are always hard downward and only {@code allow_below_spec} returns parts below them.
 *
 * <p>{@code kina.search.hard-constraints} overrides the list of a family ({@code capacitor: [value, package]});
 * the deprecated {@code kina.search.strict-constraints} is still read: {@code mounting}, {@code technology} or
 * {@code elements} missing from it are removed from every family (with a warning).
 */
@Slf4j
public final class ConstraintPolicy {

    // ---- constraint names (also the keys of excluded_by_constraints_detail, except VALUE: its kind is reported)
    /** The primary value: capacitance, resistance, inductance, impedance at its frequency, frequency. */
    public static final String VALUE = "value";
    public static final String PACKAGE = "package";
    public static final String MOUNTING = "mounting";
    public static final String TECHNOLOGY = "technology";
    /** An array or network for a single-element request (resistors, capacitors, ferrite beads). */
    public static final String ELEMENTS = "elements";
    /** The component type: family (crystal vs oscillator, Schottky vs Zener...), diode and regulator subtype. */
    public static final String TYPE = "type";
    public static final String POLARITY = "polarity";
    /** The exact voltage of a Zener diode or a fixed regulator (within 2 %). */
    public static final String VOLTAGE = "voltage";
    public static final String LOAD_CAPACITANCE = "load capacitance";
    public static final String CONNECTOR_TYPE = "connector type";
    public static final String GENDER = "gender";
    public static final String POSITIONS = "positions";
    public static final String PITCH = "pitch";
    public static final String USB_TYPE = "usb type";
    public static final String PIN_CONFIGURATION = "pin configuration";
    public static final String USB_STANDARD = "usb standard";
    /**
     * The form factor class ({@link FormFactor}: chip, through_hole, chassis, power_package, power_smd) the request's
     * words or package name.
     */
    public static final String FORM_FACTOR = "form factor";
    // relaxable by default (they become hard when a family lists them in kina.search.hard-constraints)
    public static final String DIELECTRIC = "dielectric";
    public static final String TOLERANCE = "tolerance";
    public static final String ORIENTATION = "orientation";
    public static final String TCR = "tcr";
    public static final String ESR = "esr";
    public static final String DCR = "dcr";

    /** Every name a hard-constraint list may contain. */
    public static final Set<String> NAMES = Set.of(VALUE, PACKAGE, MOUNTING, TECHNOLOGY, ELEMENTS, TYPE, POLARITY,
            VOLTAGE, LOAD_CAPACITANCE, CONNECTOR_TYPE, GENDER, POSITIONS, PITCH, USB_TYPE, PIN_CONFIGURATION,
            USB_STANDARD, FORM_FACTOR, DIELECTRIC, TOLERANCE, ORIENTATION, TCR, ESR, DCR);

    /** Constraints the relaxation may loosen when they are not hard, in ladder order (DESIGN.md 3.2). */
    public static final List<String> RELAXABLE = List.of(DIELECTRIC, PACKAGE, TOLERANCE, ORIENTATION, TCR, ESR, DCR);

    // ---- policy families
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

    /** The decided table (user decision 2026-10-07): the hard constraints per family; everything else is relaxable. */
    public static final Map<String, List<String>> DEFAULT_HARD = defaults();

    private static Map<String, List<String>> defaults() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put(RESISTOR, List.of(TYPE, VALUE, PACKAGE, MOUNTING, TECHNOLOGY, ELEMENTS, FORM_FACTOR));
        m.put(CAPACITOR, List.of(TYPE, VALUE, PACKAGE, MOUNTING, TECHNOLOGY, ELEMENTS, FORM_FACTOR));
        m.put(INDUCTOR, List.of(TYPE, VALUE, MOUNTING, TECHNOLOGY, FORM_FACTOR));
        m.put(FERRITE, List.of(TYPE, VALUE, PACKAGE, MOUNTING, ELEMENTS));
        m.put(CRYSTAL, List.of(TYPE, VALUE, LOAD_CAPACITANCE, MOUNTING));
        m.put(OSCILLATOR, List.of(TYPE, VALUE, MOUNTING));
        m.put(DIODE, List.of(TYPE, VOLTAGE, PACKAGE, MOUNTING));
        m.put(TRANSISTOR, List.of(TYPE, POLARITY, PACKAGE, MOUNTING));
        m.put(REGULATOR, List.of(TYPE, VOLTAGE, PACKAGE, MOUNTING));
        m.put(CONNECTOR, List.of(TYPE, CONNECTOR_TYPE, GENDER, POSITIONS, PITCH, PACKAGE, MOUNTING));
        m.put(USB, List.of(TYPE, USB_TYPE, PIN_CONFIGURATION, USB_STANDARD, GENDER, MOUNTING));
        m.put(DEFAULT, List.of(TYPE, VALUE, PACKAGE, MOUNTING, TECHNOLOGY, ELEMENTS, POLARITY, VOLTAGE, FORM_FACTOR));
        return java.util.Collections.unmodifiableMap(m);
    }

    /** The policy with the code defaults. */
    public static final ConstraintPolicy DEFAULTS = new ConstraintPolicy(DEFAULT_HARD);

    /** The names {@code kina.search.strict-constraints} could hold. */
    private static final List<String> LEGACY_STRICT = List.of(MOUNTING, TECHNOLOGY, ELEMENTS);

    private final Map<String, Set<String>> hard;

    ConstraintPolicy(Map<String, ? extends Collection<String>> hard) {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        DEFAULT_HARD.forEach((family, names) -> m.put(family, new LinkedHashSet<>(names)));
        hard.forEach((family, names) -> m.put(family, new LinkedHashSet<>(names)));
        m.replaceAll((family, names) -> Set.copyOf(names));
        this.hard = Map.copyOf(m);
    }

    /**
     * The policy of {@code kina.search}: the code defaults, the deprecated {@code strict-constraints} folded in (a
     * warning), then the {@code hard-constraints} overrides. Unknown family keys and names are ignored with a warning.
     */
    public static ConstraintPolicy from(KinaProperties.Search search) {
        if (search == null) {
            return DEFAULTS;
        }
        Map<String, Set<String>> table = new LinkedHashMap<>();
        DEFAULT_HARD.forEach((family, names) -> table.put(family, new LinkedHashSet<>(names)));
        List<String> strict = search.strictConstraints();
        if (strict != null) {
            log.warn("kina.search.strict-constraints (KINA_STRICT_CONSTRAINTS) is deprecated, use "
                    + "kina.search.hard-constraints (DESIGN.md 3.4); read as: {} hard", strict);
            for (String name : LEGACY_STRICT) {
                if (!strict.contains(name)) {
                    table.values().forEach(names -> names.remove(name));
                }
            }
        }
        search.hardConstraints().forEach((rawFamily, rawNames) -> {
            String family = rawFamily.strip().toLowerCase(Locale.ROOT);
            if (!DEFAULT_HARD.containsKey(family)) {
                log.warn("kina.search.hard-constraints: unknown family '{}' ignored (known: {})", rawFamily,
                        DEFAULT_HARD.keySet());
                return;
            }
            Set<String> names = new LinkedHashSet<>();
            for (String raw : rawNames == null ? List.<String>of() : rawNames) {
                String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT).replace('-', ' ')
                        .replace('_', ' ');
                if (NAMES.contains(name)) {
                    names.add(name);
                } else if (!name.isEmpty()) {
                    log.warn("kina.search.hard-constraints.{}: unknown constraint '{}' ignored (known: {})", family,
                            raw, NAMES);
                }
            }
            table.put(family, names);
        });
        return new ConstraintPolicy(table);
    }

    /** The policy family of a request: its component family, {@code usb} for USB connectors, else {@code default}. */
    public static String policyFamily(ParsedQuery query) {
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

    /** The hard constraints of the request's family. */
    public Set<String> hardFor(ParsedQuery query) {
        return hard.getOrDefault(policyFamily(query), hard.get(DEFAULT));
    }

    public boolean isHard(ParsedQuery query, String name) {
        return hardFor(query).contains(name);
    }

    /** True when {@code name} may be loosened for the request: a relaxable constraint its family does not make hard. */
    public boolean isRelaxable(ParsedQuery query, String name) {
        return RELAXABLE.contains(name) && !isHard(query, name);
    }

    /** The table as configured, family by family (for documentation and {@code list_distributors}). */
    public Map<String, Set<String>> table() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        DEFAULT_HARD.keySet().forEach(family -> out.put(family, hard.get(family)));
        return out;
    }

    // ---------------------------------------------------------------- the check

    /**
     * How a part relates to the hard constraints of a request.
     *
     * @param conflicts the hard constraints whose known value the part contradicts, in check order, by the name
     *                  reported in {@code excluded_by_constraints_detail} (the primary value by its kind:
     *                  {@code capacitance}, {@code frequency}...); empty when nothing contradicts
     * @param unknown   true when a stated hard constraint (mounting, technology) could not be compared
     */
    public record Result(List<String> conflicts, boolean unknown) {

        public Result {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public boolean conflict() {
            return !conflicts.isEmpty();
        }

        /** The constraint a part is counted under: its first conflict. */
        public String reason() {
            return conflicts.isEmpty() ? null : conflicts.getFirst();
        }
    }

    /** {@link #check(ParsedQuery, ParametricExtractor.Features, Collection)} with the request family's hard set. */
    Result check(ParsedQuery query, ParametricExtractor.Features f) {
        return check(query, f, hardFor(query));
    }

    /**
     * Checks the stated constraints in {@code hard} against the part's known features. An attribute the part does not
     * state never conflicts (it is unverified, the part ranks below verified matches); a package string KINA cannot
     * read never conflicts either ({@link Recognizers#samePackage}).
     */
    static Result check(ParsedQuery query, ParametricExtractor.Features f, Collection<String> hard) {
        List<String> conflicts = new ArrayList<>();
        boolean unknown = false;
        if (hard == null || hard.isEmpty()) {
            return new Result(conflicts, false);
        }
        if (hard.contains(TYPE) && typeConflict(query, f)) {
            conflicts.add(TYPE);
        }
        if (hard.contains(POLARITY) && query.polarity() != null && f.polarity() != null
                && !query.polarity().equals(f.polarity())) {
            conflicts.add(POLARITY);
        }
        String primary = query.isConnector() ? null : DeterministicRanker.primaryKind(query);
        if (hard.contains(VALUE) && primary != null && !DeterministicRanker.primaryMatches(query, f, primary)) {
            conflicts.add(primary.replace('_', ' '));
        }
        if (hard.contains(VOLTAGE) && DeterministicRanker.exactVoltageMatches(query, f) == Boolean.FALSE) {
            conflicts.add(VOLTAGE);
        }
        if (hard.contains(LOAD_CAPACITANCE) && DeterministicRanker.loadCapacitanceMatches(query, f) == Boolean.FALSE) {
            conflicts.add(LOAD_CAPACITANCE);
        }
        if (hard.contains(PACKAGE) && Recognizers.samePackage(query.packageName(), f.packageName()) == Boolean.FALSE) {
            conflicts.add(PACKAGE);
        }
        if (hard.contains(MOUNTING) && query.mounting() != null) {
            boolean hybrid = f.connector() != null && UsbVocabulary.HYBRID.equals(f.connector().mountingStyle());
            if (f.mounting() == null || hybrid) {
                unknown = true;
            } else if (!query.mounting().equals(f.mounting())) {
                conflicts.add(MOUNTING);
            }
        }
        if (hard.contains(TECHNOLOGY) && query.technology() != null) {
            int cmp = TechnologyVocabulary.compare(query.technology(), f.technology());
            if (cmp < 0) {
                conflicts.add(TECHNOLOGY);
            }
            unknown |= cmp == 0;
        }
        if (hard.contains(FORM_FACTOR)) {
            Boolean same = FormFactor.compatible(FormFactor.ofRequest(query, hard.contains(PACKAGE)), f.formFactor());
            if (same == Boolean.FALSE) {
                conflicts.add(FORM_FACTOR);
            }
            unknown |= same == null && query.formFactor() != null;
        }
        if (hard.contains(ELEMENTS) && query.elements() == null && f.elements() != null && query.family() != null
                && PassiveDetails.ARRAY_FAMILIES.contains(query.family())) {
            conflicts.add(ELEMENTS);   // a bead array or resistor network for a single-element request
        }
        connectorConflicts(query, f, hard, conflicts);
        if (hard.contains(DIELECTRIC) && query.dielectric() != null && f.dielectric() != null
                && !query.dielectric().equalsIgnoreCase(f.dielectric())) {
            conflicts.add(DIELECTRIC);
        }
        ParsedQuery.Constraint tolerance = query.constraint(ParsedQuery.TOLERANCE);
        Double partTolerance = f.value(ParsedQuery.TOLERANCE);
        if (hard.contains(TOLERANCE) && tolerance != null && partTolerance != null
                && partTolerance > tolerance.value() + 1e-9) {
            conflicts.add(TOLERANCE);
        }
        return new Result(conflicts, unknown);
    }

    private static final Set<String> DIODE_KINDS = Set.of("schottky", "zener", "tvs", "led");

    /**
     * The component type: known families that are not the same or a specialisation of one another (a crystal is no
     * oscillator, a Zener no TVS diode), a standard rectifier or switching diode against a Schottky, Zener, TVS or LED
     * (either way), a fixed against an adjustable regulator.
     */
    static boolean typeConflict(ParsedQuery query, ParametricExtractor.Features f) {
        String wanted = query.family();
        String actual = f.family();
        if (!Recognizers.compatibleFamilies(wanted, actual)) {
            return true;
        }
        if ("diode".equals(wanted) && ComponentTypes.STANDARD.equals(query.subtype()) && actual != null
                && DIODE_KINDS.contains(actual)) {
            return true;
        }
        if (wanted != null && DIODE_KINDS.contains(wanted) && "diode".equals(actual)
                && ComponentTypes.STANDARD.equals(f.subtype())) {
            return true;
        }
        return "regulator".equals(wanted) && query.subtype() != null && f.subtype() != null
                && !query.subtype().equals(f.subtype());
    }

    private static void connectorConflicts(ParsedQuery query, ParametricExtractor.Features f, Collection<String> hard,
                                           List<String> conflicts) {
        ParsedQuery.Connector wanted = query.connector();
        ParsedQuery.Connector actual = f.connector();
        if (wanted == null || actual == null) {
            return;
        }
        if (wanted.isUsb()) {
            String wantedType = wanted.usbType() != null ? wanted.usbType() : UsbVocabulary.usbTypeOf(wanted.type());
            String actualType = actual.usbType() != null ? actual.usbType() : UsbVocabulary.usbTypeOf(actual.type());
            boolean otherConnector = !actual.isUsb() && actual.type() != null
                    && !ParsedQuery.CONNECTOR.equals(actual.type());
            if (hard.contains(USB_TYPE) && wantedType != null
                    && (actualType != null && !wantedType.equals(actualType) || otherConnector)) {
                conflicts.add(USB_TYPE);
            }
            if (hard.contains(PIN_CONFIGURATION) && !wanted.pinConfigurationImplied()) {
                Integer wantedPins = wanted.pinConfiguration() != null ? wanted.pinConfiguration()
                        : UsbVocabulary.configuration(wantedType, wanted.positions());
                if (wantedPins == null) {
                    wantedPins = wanted.positions();
                }
                Integer actualPins = actual.pinConfiguration() != null ? actual.pinConfiguration()
                        : UsbVocabulary.configuration(wantedType, actual.positions());
                if (wantedPins != null && actualPins != null && !wantedPins.equals(actualPins)) {
                    conflicts.add(PIN_CONFIGURATION);
                }
            }
            UsbVocabulary.Standard wantedStandard = UsbVocabulary.standard(wanted.usbStandard());
            if (hard.contains(USB_STANDARD) && wantedStandard != null) {
                boolean powerOnly = actual.hasFeature(UsbVocabulary.POWER_ONLY) && actual.usbStandard() == null;
                Double cmp = UsbVocabulary.compare(wantedStandard, UsbVocabulary.standard(actual.usbStandard()));
                if (powerOnly || cmp != null && cmp <= 0) {
                    conflicts.add(USB_STANDARD);   // a lower standard; a higher one is fine
                }
            }
        } else {
            if (hard.contains(CONNECTOR_TYPE)
                    && ConnectorRecognizer.typesMatch(wanted.type(), actual.type()) == Boolean.FALSE) {
                conflicts.add(CONNECTOR_TYPE);
            }
            if (hard.contains(POSITIONS) && wanted.positions() != null && actual.positions() != null
                    && !wanted.positions().equals(actual.positions())) {
                conflicts.add(POSITIONS);
            }
            if (hard.contains(PITCH) && wanted.pitchMm() != null && actual.pitchMm() != null
                    && Math.abs(wanted.pitchMm() - actual.pitchMm()) > DeterministicRanker.PITCH_TOLERANCE_MM) {
                conflicts.add(PITCH);
            }
        }
        if (hard.contains(GENDER) && wanted.gender() != null && actual.gender() != null
                && !wanted.gender().equals(actual.gender())) {
            conflicts.add(GENDER);
        }
        if (hard.contains(ORIENTATION) && wanted.orientation() != null && actual.orientation() != null
                && !wanted.orientation().equals(actual.orientation())) {
            conflicts.add(ORIENTATION);
        }
    }

    // ---------------------------------------------------------------- reporting

    /**
     * The stated hard constraints of a request by their reported name (the primary value by its kind), in the order
     * of {@link #check}; the component type is left out (every request has one). A hint names them next to the
     * constraints that excluded parts.
     */
    public List<String> statedHard(ParsedQuery query) {
        Set<String> hardSet = hardFor(query);
        List<String> out = new ArrayList<>();
        if (hardSet.contains(POLARITY) && query.polarity() != null) {
            out.add(POLARITY);
        }
        String primary = query.isConnector() ? null : DeterministicRanker.primaryKind(query);
        if (hardSet.contains(VALUE) && primary != null) {
            out.add(primary.replace('_', ' '));
        }
        if (hardSet.contains(VOLTAGE) && query.constraint(ParsedQuery.VOLTAGE) != null
                && DeterministicRanker.isExactRating(ParsedQuery.VOLTAGE, query.family())) {
            out.add(VOLTAGE);
        }
        if (hardSet.contains(LOAD_CAPACITANCE) && DeterministicRanker.isLoadCapacitance(query)) {
            out.add(LOAD_CAPACITANCE);
        }
        if (hardSet.contains(PACKAGE) && query.packageName() != null) {
            out.add(PACKAGE);
        }
        if (hardSet.contains(MOUNTING) && query.mounting() != null) {
            out.add(MOUNTING);
        }
        if (hardSet.contains(TECHNOLOGY) && query.technology() != null) {
            out.add(TECHNOLOGY);
        }
        if (hardSet.contains(FORM_FACTOR) && query.formFactor() != null) {
            out.add(FORM_FACTOR);
        }
        ParsedQuery.Connector c = query.connector();
        if (c != null) {
            if (c.isUsb()) {
                if (hardSet.contains(USB_TYPE) && c.usbType() != null) {
                    out.add(USB_TYPE);
                }
                if (hardSet.contains(PIN_CONFIGURATION) && c.pinConfiguration() != null
                        && !c.pinConfigurationImplied()) {
                    out.add(PIN_CONFIGURATION);
                }
                if (hardSet.contains(USB_STANDARD) && c.usbStandard() != null) {
                    out.add(USB_STANDARD);
                }
            } else {
                if (hardSet.contains(CONNECTOR_TYPE) && c.type() != null && !ParsedQuery.CONNECTOR.equals(c.type())) {
                    out.add(CONNECTOR_TYPE);
                }
                if (hardSet.contains(POSITIONS) && c.positions() != null) {
                    out.add(POSITIONS);
                }
                if (hardSet.contains(PITCH) && c.pitchMm() != null) {
                    out.add(PITCH);
                }
            }
            if (hardSet.contains(GENDER) && c.gender() != null) {
                out.add(GENDER);
            }
        }
        return out;
    }

    /**
     * A short description of the request for a hint: the primary value, the polarity or subtype, the family and the
     * package ({@code 22uF capacitor in package 1206}, {@code N-channel mosfet in package SOT-23}).
     */
    static String describe(ParsedQuery query) {
        List<String> words = new ArrayList<>();
        String primary = query.isConnector() ? null : DeterministicRanker.primaryKind(query);
        if (primary != null) {
            words.add(query.constraint(primary).display());
        }
        if (query.constraint(ParsedQuery.VOLTAGE) != null
                && DeterministicRanker.isExactRating(ParsedQuery.VOLTAGE, query.family())) {
            words.add(query.constraint(ParsedQuery.VOLTAGE).display());
        }
        if (query.polarity() != null) {
            words.add(query.polarity());
        }
        if (query.subtype() != null) {
            words.add(query.subtype());
        }
        ParsedQuery.Connector c = query.connector();
        if (c != null) {
            if (c.positions() != null) {
                words.add(c.positions() + "-position");
            }
            words.add(c.usbType() != null ? "USB " + c.usbType() : c.type() != null ? c.type() : "connector");
        } else {
            words.add(query.family() != null ? query.family() : "part");
        }
        String text = String.join(" ", words);
        return query.packageName() != null ? text + " in package " + query.packageName() : text;
    }

    /**
     * The hint of distributors that returned nothing (DESIGN.md 3.2 "Empty after the hard set"): what was asked, at
     * which distributors, which hard constraints could not be met (those that excluded parts, else the stated ones),
     * how many parts were below a stated rating, and that no substitutes are returned.
     *
     * @param distributors   the distributors that returned nothing (display names)
     * @param excludedBy     parts excluded per constraint, summed over those distributors
     * @param belowSpec      parts below a stated rating, summed over those distributors
     * @param allowBelowSpec whether the request already returns parts below spec
     */
    String hint(ParsedQuery query, List<String> distributors, Map<String, Integer> excludedBy, int belowSpec,
                boolean allowBelowSpec) {
        List<String> names = new ArrayList<>();
        excludedBy.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> names.add(e.getKey()));
        for (String stated : statedHard(query)) {
            if (!names.contains(stated)) {
                names.add(stated);
            }
        }
        StringBuilder out = new StringBuilder("No in-stock ").append(describe(query)).append(" at ")
                .append(joinAnd(distributors));
        if (!names.isEmpty()) {
            out.append("; ").append(joinAnd(names)).append(names.size() == 1 ? " is" : " are")
                    .append(" never relaxed");
        }
        out.append('.');
        if (belowSpec > 0 && !allowBelowSpec) {
            out.append(' ').append(belowSpec).append(belowSpec == 1 ? " part was" : " parts were")
                    .append(" left out for a rating below the request; pass \"allow_below_spec\": true to see them.");
        }
        List<String> alternatives = new ArrayList<>();
        if (names.contains(PACKAGE)) {
            alternatives.add("package");
        }
        String primary = query.isConnector() ? null : DeterministicRanker.primaryKind(query);
        if (primary != null && names.contains(primary.replace('_', ' ')) || names.contains(VOLTAGE)) {
            alternatives.add("value");
        }
        out.append(" No substitutes are returned; try another ")
                .append(alternatives.isEmpty() ? "wording" : String.join(" or ", alternatives)).append('.');
        return out.toString();
    }

    private static String joinAnd(List<String> items) {
        if (items.size() <= 1) {
            return items.isEmpty() ? "" : items.getFirst();
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
