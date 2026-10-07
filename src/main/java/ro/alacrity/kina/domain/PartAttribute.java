package ro.alacrity.kina.domain;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every attribute KINA reads from a part, with its distributor spellings and extraction logic ({@link Source}) and,
 * for a numeric attribute, its unit ({@link Unit}), declared on the constant (DESIGN.md 3.4 "Attribute sources").
 * {@code ParametricExtractor} reads the attributes through these declarations; add a distributor spelling to the
 * constant's {@link Source#names()}, not to the extractor.
 *
 * <p>The attributes are not the {@link ConstraintKind}s: one attribute can feed several kinds (the voltage is the
 * minimum rating of most parts and the exact voltage of a Zener or a fixed regulator; the capacitance, resistance,
 * inductance, impedance and frequency are each the primary {@code VALUE}), and some attributes only feed others (the
 * test frequency of an impedance, the pinout layout of a connector).
 *
 * <p>The numeric attributes ({@link #kind()} not null) are read with the value family (the family the category or
 * description names, before the values decide it); the others with the part's family.
 */
public enum PartAttribute {

    // ---------------------------------------------------------------- numeric attributes, in output order

    @Unit(symbols = "f", base = "F", prefixes = {"p", "n", "u", "m", ""})
    @Source(names = {"capacitance", "capacitance value", "nominal capacitance", "load capacitance",
            "load capacitance (cl)"})
    CAPACITANCE(ParsedQuery.CAPACITANCE, "Capacitance"),

    @Unit(symbols = {"ohm", "ohms", "r"}, base = "ohm", prefixes = {"m", "", "k", "M", "G"})
    RESISTANCE(ParsedQuery.RESISTANCE, "Resistance"),

    @Unit(symbols = "h", base = "H", prefixes = {"n", "u", "m", ""})
    @Source(names = {"inductance", "nominal inductance"})
    INDUCTANCE(ParsedQuery.INDUCTANCE, "Inductance"),

    @Unit(base = "ohm", prefixes = {"m", "", "k", "M", "G"})
    IMPEDANCE(ParsedQuery.IMPEDANCE, "Impedance"),

    @Unit(symbols = "hz", base = "Hz", prefixes = {"", "k", "M", "G"})
    @Source(names = {"frequency", "nominal frequency", "oscillation frequency"})
    FREQUENCY(ParsedQuery.FREQUENCY, "Frequency"),

    @Unit(symbols = {"v", "vdc", "vac", "volt", "volts", "vol", "vo"}, base = "V", prefixes = {"u", "m", "", "k"})
    VOLTAGE(ParsedQuery.VOLTAGE, "Voltage"),

    @Unit(symbols = "a", base = "A", prefixes = {"u", "m", "", "k"})
    CURRENT(ParsedQuery.CURRENT, "Current"),

    @Unit(base = "A", prefixes = {"u", "m", "", "k"})
    SATURATION_CURRENT(ParsedQuery.SATURATION_CURRENT, "SaturationCurrent"),

    @Unit(base = "ohm", prefixes = {"m", "", "k", "M", "G"})
    DCR(ParsedQuery.DCR, "DCR"),

    @Unit(symbols = {"w", "watt", "watts"}, base = "W", prefixes = {"", "k"})
    @Source(names = {"power rating", "power", "power(watts)", "power (watts)", "pd - power dissipation",
            "power dissipation (pd)", "power dissipation"})
    POWER(ParsedQuery.POWER, "Power"),

    @Unit(base = "°C")
    TEMPERATURE(ParsedQuery.TEMPERATURE, "MaxTemperature"),

    @Unit(base = "h")
    LIFETIME(ParsedQuery.LIFETIME, "Lifetime"),

    @Unit(base = "%")
    @Source(names = {"tolerance", "resistance tolerance", "capacitance tolerance", "inductance tolerance"})
    TOLERANCE(ParsedQuery.TOLERANCE, "Tolerance");

    /** The numeric attributes the extractor reports, in output order. */
    public static final List<PartAttribute> VALUES = List.of(CAPACITANCE, RESISTANCE, INDUCTANCE, IMPEDANCE,
            FREQUENCY, VOLTAGE, CURRENT, SATURATION_CURRENT, DCR, POWER, TEMPERATURE, LIFETIME, TOLERANCE);

    private final String kind;
    private final String key;

    PartAttribute(String kind, String key) {
        this.kind = kind;
        this.key = key;
    }

    /** The {@link ParsedQuery} value kind of a numeric attribute, null for a word or text attribute. */
    public String kind() {
        return kind;
    }

    /** The canonical attribute name the extractor reports it under, null when it is not reported by itself. */
    public String key() {
        return key;
    }

    /** True for the numeric attributes: they are read with the value family, the others with the part's family. */
    public boolean readsWithValueFamily() {
        return kind != null;
    }

    /** The unit, null for an attribute without one. */
    public Unit unit() {
        return Declarations.UNITS.get(this);
    }

    /**
     * The kind a value of this attribute is parsed as: the kind of the attribute that owns the symbols of its unit
     * ({@code DCR} is parsed in ohm, as a resistance), else its own kind.
     */
    public String readKind() {
        Unit unit = unit();
        if (unit == null || unit.symbols().length > 0) {
            return kind;
        }
        for (PartAttribute a : values()) {
            Unit u = a.unit();
            if (u != null && u.symbols().length > 0 && u.base().equals(unit.base())) {
                return a.kind;
            }
        }
        return kind;
    }

    /** The sources, in precedence order. */
    public List<Source> sources() {
        return Declarations.SOURCES.get(this).stream().map(Declared::source).toList();
    }

    /** Every name of the sources, in precedence order. */
    public List<String> names() {
        return sources().stream().flatMap(s -> Arrays.stream(s.names())).toList();
    }

    /** The unit of a {@link ParsedQuery} value kind, null when no attribute of that kind declares one. */
    public static Unit unitOf(String kind) {
        for (PartAttribute a : values()) {
            if (a.kind != null && a.kind.equals(kind) && a.unit() != null) {
                return a.unit();
            }
        }
        return null;
    }

    /** The {@link ParsedQuery} kind of each unit symbol ({@code "vdc"} -&gt; voltage), in declaration order. */
    public static Map<String, String> unitSymbols() {
        return Declarations.SYMBOLS;
    }

    /** The attribute reported under a canonical name, null for none. */
    public static PartAttribute ofKey(String key) {
        for (PartAttribute a : VALUES) {
            if (a.key.equals(key)) {
                return a;
            }
        }
        return null;
    }

    /**
     * Reads the attribute: the first applicable source (precedence order) whose logic yields a value.
     *
     * @param family the family the attribute is read with ({@link #readsWithValueFamily()})
     * @return the value and the source that read it, null when no source reads one
     */
    public Reading read(PartSource part, String family, ExtractionContext ctx) {
        for (Declared d : Declarations.SOURCES.get(this)) {
            if (d.applies(part.distributor(), family)) {
                Optional<?> value = d.logic().extract(part, new AttributeLogic.Lookup(this, d.source(), family), ctx);
                if (value.isPresent()) {
                    return new Reading(value.get(), d.source(), d.logic());
                }
            }
        }
        return null;
    }

    /**
     * A value read from a part.
     *
     * @param source the source that read it
     */
    public record Reading(Object value, Source source, AttributeLogic logic) {

        /** True when the value comes from the distributor's attributes or fields (not from the description). */
        public boolean statedByAttributes() {
            return logic.readsAttributes();
        }
    }

    /** A source with its logic instance. */
    private record Declared(Source source, AttributeLogic logic) {

        boolean applies(Distributor distributor, String family) {
            if (source.distributors().length > 0 && !Arrays.asList(source.distributors()).contains(distributor)) {
                return false;
            }
            if (source.families().length > 0
                    && Arrays.stream(source.families()).noneMatch(f -> f.label().equals(family))) {
                return false;
            }
            for (ComponentFamily.Trait t : source.traits()) {
                if (!ComponentFamily.has(family, t)) {
                    return false;
                }
            }
            for (ComponentFamily.Trait t : source.exceptTraits()) {
                if (ComponentFamily.has(family, t)) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The declarations, read once by reflection (after the constants exist). */
    private static final class Declarations {

        private static final Map<Class<? extends AttributeLogic>, AttributeLogic> LOGIC = new ConcurrentHashMap<>();
        static final Map<PartAttribute, Unit> UNITS = new EnumMap<>(PartAttribute.class);
        static final Map<PartAttribute, List<Declared>> SOURCES = new EnumMap<>(PartAttribute.class);
        static final Map<String, String> SYMBOLS;

        static {
            Map<String, String> symbols = new LinkedHashMap<>();
            for (PartAttribute a : values()) {
                Field field = field(a);
                Unit unit = field.getAnnotation(Unit.class);
                if (unit != null) {
                    UNITS.put(a, unit);
                    for (String symbol : unit.symbols()) {
                        if (symbols.putIfAbsent(symbol, a.kind) != null) {
                            throw new IllegalStateException("unit symbol declared twice: " + symbol);
                        }
                    }
                }
                SOURCES.put(a, Arrays.stream(field.getAnnotationsByType(Source.class))
                        .sorted(Comparator.comparingInt(Source::precedence))
                        .map(s -> new Declared(s, LOGIC.computeIfAbsent(s.logic(), Declarations::instance)))
                        .toList());
            }
            SYMBOLS = java.util.Collections.unmodifiableMap(symbols);
        }

        private static Field field(PartAttribute a) {
            try {
                return PartAttribute.class.getField(a.name());
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(e);
            }
        }

        private static AttributeLogic instance(Class<? extends AttributeLogic> type) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                     | InvocationTargetException e) {
                throw new IllegalStateException("attribute logic " + type.getName() + " needs a public no-arg constructor",
                        e);
            }
        }
    }
}
