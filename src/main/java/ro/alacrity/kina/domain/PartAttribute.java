package ro.alacrity.kina.domain;

import ro.alacrity.kina.domain.extract.CanDimensions;
import ro.alacrity.kina.domain.extract.ConnectorOrientation;
import ro.alacrity.kina.domain.extract.ConnectorTypeWord;
import ro.alacrity.kina.domain.extract.Described;
import ro.alacrity.kina.domain.extract.DescribedWord;
import ro.alacrity.kina.domain.extract.DescriptionDimensions;
import ro.alacrity.kina.domain.extract.DescriptionTemperatureRange;
import ro.alacrity.kina.domain.extract.DielectricCode;
import ro.alacrity.kina.domain.extract.Dimensions;
import ro.alacrity.kina.domain.extract.ElementsCount;
import ro.alacrity.kina.domain.extract.FirstInteger;
import ro.alacrity.kina.domain.extract.GenderWord;
import ro.alacrity.kina.domain.extract.ImpedanceAtFrequency;
import ro.alacrity.kina.domain.extract.IpCode;
import ro.alacrity.kina.domain.extract.KeyContaining;
import ro.alacrity.kina.domain.extract.LedPackage;
import ro.alacrity.kina.domain.extract.LargestVoltage;
import ro.alacrity.kina.domain.extract.LayoutPositions;
import ro.alacrity.kina.domain.extract.LayoutRows;
import ro.alacrity.kina.domain.extract.LifetimeAtTemperature;
import ro.alacrity.kina.domain.extract.MaxTemperature;
import ro.alacrity.kina.domain.extract.MergedWords;
import ro.alacrity.kina.domain.extract.MetricPackageCode;
import ro.alacrity.kina.domain.extract.Millimetres;
import ro.alacrity.kina.domain.extract.MountingWord;
import ro.alacrity.kina.domain.extract.OhmsAtFrequency;
import ro.alacrity.kina.domain.extract.OhmsAtKeyPrefix;
import ro.alacrity.kina.domain.extract.PackageCode;
import ro.alacrity.kina.domain.extract.PackageField;
import ro.alacrity.kina.domain.extract.PackageFieldDimensions;
import ro.alacrity.kina.domain.extract.PackageFieldMounting;
import ro.alacrity.kina.domain.extract.PackageFieldPrefix;
import ro.alacrity.kina.domain.extract.PackageFieldWord;
import ro.alacrity.kina.domain.extract.PartNumberPackage;
import ro.alacrity.kina.domain.extract.PitchValue;
import ro.alacrity.kina.domain.extract.RawPackageField;
import ro.alacrity.kina.domain.extract.SeriesPower;
import ro.alacrity.kina.domain.extract.ShortSeries;
import ro.alacrity.kina.domain.extract.StatedGender;
import ro.alacrity.kina.domain.extract.SupplyVoltage;
import ro.alacrity.kina.domain.extract.TemperatureRange;
import ro.alacrity.kina.domain.extract.VocabularyWord;

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

import static ro.alacrity.kina.domain.ComponentFamily.FAN;
import static ro.alacrity.kina.domain.ComponentFamily.LED;
import static ro.alacrity.kina.domain.ComponentFamily.SWITCH;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.ARRAYS;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.INDUCTIVE;
import static ro.alacrity.kina.domain.ComponentFamily.Trait.PASSIVE;
import static ro.alacrity.kina.domain.Indexed.ColumnType.FLOAT8;

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

    /** Capacitance (also the load capacitance of a crystal). */
    @Unit(symbols = "f", base = "F", prefixes = {"p", "n", "u", "m", ""})
    @Source(names = {"capacitance", "capacitance value", "nominal capacitance", "load capacitance",
            "load capacitance (cl)"})
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "capacitance_f", type = FLOAT8)
    CAPACITANCE(ParsedQuery.CAPACITANCE, "Capacitance"),

    /**
     * Resistance; for a MOSFET its on-resistance (Mouser {@code Rds On - Drain-Source Resistance}). Never for an
     * inductor or ferrite bead: their ohm values are the DC resistance or the impedance.
     */
    @Unit(symbols = {"ohm", "ohms", "r"}, base = "ohm", prefixes = {"m", "", "k", "M", "G"})
    @Source(names = {"resistance", "resistance value", "nominal resistance", "rds on - drain-source resistance",
            "drain-source on resistance", "on-state resistance", "rds(on)"}, exceptTraits = INDUCTIVE)
    @Source(precedence = 9, logic = Described.class, exceptTraits = INDUCTIVE)
    @Indexed(column = "resistance_ohm", type = FLOAT8)
    RESISTANCE(ParsedQuery.RESISTANCE, "Resistance"),

    @Unit(symbols = "h", base = "H", prefixes = {"n", "u", "m", ""})
    @Source(names = {"inductance", "nominal inductance"})
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "inductance_h", type = FLOAT8)
    INDUCTANCE(ParsedQuery.INDUCTANCE, "Inductance"),

    /**
     * A ferrite bead's impedance with its test frequency ({@code 120ohm @100MHz}): attributes whose name starts with
     * {@code impedance} (TME {@code Impedance at 100MHz}, Mouser {@code Impedance} with {@link #TEST_FREQUENCY}).
     */
    @Unit(base = "ohm", prefixes = {"m", "", "k", "M", "G"})
    @Source(names = "impedance", families = ComponentFamily.FERRITE, logic = ImpedanceAtFrequency.class)
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "impedance_ohm", type = FLOAT8, condition = "impedance_test_hz")
    IMPEDANCE(ParsedQuery.IMPEDANCE, "Impedance"),

    @Unit(symbols = "hz", base = "Hz", prefixes = {"", "k", "M", "G"})
    @Source(names = {"frequency", "nominal frequency", "oscillation frequency"})
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "frequency_hz", type = FLOAT8)
    FREQUENCY(ParsedQuery.FREQUENCY, "Frequency"),

    /**
     * The voltage: a regulator's output voltage (TME {@code Output voltage}, Mouser {@code Output Voltage}) first, a
     * Zener diode's Zener voltage (Mouser {@code Vz - Zener Voltage}) first, a fan's supply voltage (TME
     * {@code Supply voltage} = {@code 12V DC}, not its {@code Operating voltage} range) first, then the voltage ratings (Mouser
     * {@code Voltage Rating DC}, TME {@code Operating voltage}, LCSC {@code Voltage Rated}), then any attribute named
     * with {@code voltage} that is no forward, clamp, breakdown... voltage, last the description (for a transistor or
     * diode its largest unlabelled voltage, {@link LargestVoltage}).
     */
    @Unit(symbols = {"v", "vdc", "vac", "volt", "volts", "vol", "vo"}, base = "V", prefixes = {"u", "m", "", "k"})
    @Source(names = {"output voltage", "voltage - output", "voltage - output (min/fixed)", "output voltage (fixed)",
            "fixed output voltage"}, families = ComponentFamily.REGULATOR)
    @Source(precedence = 1, names = {"vz - zener voltage", "zener voltage", "voltage - zener (nom) (vz)",
            "zener voltage (vz)", "voltage - zener"}, families = ComponentFamily.ZENER)
    @Source(precedence = 1, names = {"supply voltage", "rated voltage", "nominal voltage", "voltage rating dc",
            "voltage rating", "voltage"}, families = FAN)
    @Source(precedence = 2, names = {"voltage rating dc", "voltage rating - dc", "voltage rating", "voltage rated",
            "rated voltage", "voltage - rated", "operating voltage", "dc voltage rating", "voltage", "output voltage",
            "voltage - output", "voltage - output (min/fixed)", "vr - reverse voltage", "reverse voltage (vr)",
            "vds - drain-source breakdown voltage", "drain source voltage (vdss)", "drain to source voltage (vdss)",
            "vz - zener voltage", "voltage - zener (nom) (vz)", "vrwm - reverse standoff voltage",
            "reverse stand-off voltage (vrwm)", "voltage - reverse standoff (typ)"})
    @Source(precedence = 3, names = "voltage", logic = KeyContaining.class, excluding = {"forward", "clamp",
            "breakdown", "input", "supply", "isolation", "threshold", "gate", "ripple", "dropout", "temperature",
            "coefficient", "offset"})
    @Source(precedence = 9, logic = LargestVoltage.class)
    @Indexed(column = "voltage_v", type = FLOAT8)
    VOLTAGE(ParsedQuery.VOLTAGE, "Voltage"),

    /**
     * The current; for an inductor or ferrite bead its rated current (TME {@code Operating current}, Mouser
     * {@code Maximum DC Current}) first, reported as {@code RatedCurrent}. Then the current ratings, then any attribute
     * named with {@code current} that is no leakage, surge, peak... current, last the description.
     */
    @Unit(symbols = "a", base = "A", prefixes = {"u", "m", "", "k"})
    @Source(names = {"rated current", "current rating", "operating current", "maximum dc current", "max. dc current",
            "dc current", "current - max", "current rating (amps)", "irms", "i rms", "rated current (irms)", "current"},
            traits = INDUCTIVE)
    @Source(precedence = 1, names = {"current rating", "rated current", "current", "current - output",
            "output current", "id - continuous drain current", "continuous drain current (id)", "if - forward current",
            "io - average rectified current", "current - average rectified (io)", "average rectified current (io)",
            "ic - continuous collector current", "collector current (ic)", "current rating (amps)"})
    @Source(precedence = 2, names = "current", logic = KeyContaining.class, excluding = {"leakage",
            "reverse current", "surge", "quiescent", "supply", "peak", "bias", "offset", "standby", "pulse", "trip",
            "saturation", "ripple"})
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "current_a", type = FLOAT8)
    CURRENT(ParsedQuery.CURRENT, "Current"),

    /** An inductor's saturation current (I_sat). */
    @Unit(base = "A", prefixes = {"u", "m", "", "k"})
    @Source(names = {"saturation current", "isat", "current - saturation", "current - saturation (isat)",
            "saturation current (isat)", "isat (max)", "saturation current max."}, traits = INDUCTIVE)
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "isat_a", type = FLOAT8)
    SATURATION_CURRENT(ParsedQuery.SATURATION_CURRENT, "SaturationCurrent"),

    /** The DC resistance of an inductor or ferrite bead (TME {@code Resistance}, Mouser {@code Maximum DC Resistance}). */
    @Unit(base = "ohm", prefixes = {"m", "", "k", "M", "G"})
    @Source(names = {"dc resistance", "dc resistance (dcr)", "dcr", "maximum dc resistance", "max. dc resistance",
            "dc resistance max", "dc resistance (dcr) (max)", "resistance - dc", "resistance", "dc resistance (max)"},
            traits = INDUCTIVE)
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "dcr_ohm", type = FLOAT8)
    DCR(ParsedQuery.DCR, "DCR"),

    /** The power rating; after the description, the wattage a resistor series implies ({@link SeriesPower}). */
    @Unit(symbols = {"w", "watt", "watts"}, base = "W", prefixes = {"", "k"})
    @Source(names = {"power rating", "power", "power(watts)", "power (watts)", "pd - power dissipation",
            "power dissipation (pd)", "power dissipation"})
    @Source(precedence = 8, logic = Described.class)
    @Source(precedence = 9, logic = SeriesPower.class)
    @Indexed(column = "power_w", type = FLOAT8)
    POWER(ParsedQuery.POWER, "Power"),

    /** The maximum operating temperature ({@code 105°C}). */
    @Unit(base = "°C")
    @Source(names = {"maximum operating temperature", "max. operating temperature", "operating temperature",
            "operating temperature range", "temperature range"}, logic = MaxTemperature.class)
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "max_temp_c", type = FLOAT8)
    TEMPERATURE(ParsedQuery.TEMPERATURE, "MaxTemperature"),

    /** The rated lifetime in hours with its test temperature ({@code 2000h @105°C}). */
    @Unit(base = "h", display = ValueDisplay.HoursAtTemperature.class)
    @Source(names = {"service life", "lifetime", "life time", "load life", "endurance", "useful life",
            "lifetime @ temp.", "life", "operating life"}, logic = LifetimeAtTemperature.class)
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "lifetime_h", type = FLOAT8)
    LIFETIME(ParsedQuery.LIFETIME, "Lifetime"),

    @Unit(base = "%", display = ValueDisplay.Percent.class)
    @Source(names = {"tolerance", "resistance tolerance", "capacitance tolerance", "inductance tolerance"})
    @Source(precedence = 9, logic = Described.class)
    @Indexed(column = "tolerance_pct", type = FLOAT8)
    TOLERANCE(ParsedQuery.TOLERANCE, "Tolerance"),

    /** The rotational speed of a fan ({@code 3000 rpm}; {@code r/min} is read as rpm; TME {@code Rotational rate/speed}). */
    @Unit(symbols = "rpm", base = "rpm", display = ValueDisplay.Spaced.class, families = FAN)
    @Source(names = {"rotational rate/speed", "rotational speed", "speed", "fan speed", "rated speed",
            "speed (rpm)", "nominal speed"}, families = FAN)
    @Source(precedence = 9, logic = Described.class, families = FAN)
    @Indexed(column = Indexed.ATTRS, keys = "speed", type = FLOAT8)
    SPEED(ParsedQuery.SPEED, "Speed"),

    /**
     * The airflow of a fan in m³/h, shown with its CFM ({@code 68 m³/h (40 CFM)}); {@code m3/h} is read as m3h. TME
     * calls it {@code Fan efficiency} ({@code 13.52m<sup>3</sup>/h}); Mouser and LCSC state it in the description.
     */
    @Unit(symbols = {"cfm", "m3h", "m3min", "lmin"}, factors = {1.699011, 1, 60, 0.06}, base = "m³/h",
            display = ValueDisplay.WithAlternative.class, alternative = "CFM", families = FAN)
    @Source(names = {"fan efficiency", "air flow", "air flow rate", "max air flow", "maximum air flow", "air volume"},
            families = FAN)
    @Source(precedence = 9, logic = Described.class, families = FAN)
    @Indexed(column = Indexed.ATTRS, keys = "airflow", type = FLOAT8)
    AIRFLOW(ParsedQuery.AIRFLOW, "Airflow"),

    /**
     * The static pressure of a fan in pascal, shown with its mmH2O ({@code 24.5 Pa (2.5 mmH2O)}): TME
     * {@code 4.83mm H<sub>2</sub>O}, Mouser {@code 0.25"H2O} (inches of water).
     */
    @Unit(symbols = {"pa", "mmh2o", "mmaq", "inh2o"}, factors = {1, 9.80665, 9.80665, 249.089}, base = "Pa",
            display = ValueDisplay.WithAlternative.class, alternative = "mmH2O", families = FAN)
    @Source(names = {"static pressure", "max static pressure", "maximum static pressure", "static air pressure",
            "air pressure"}, families = FAN)
    @Source(precedence = 9, logic = Described.class, families = FAN)
    @Indexed(column = Indexed.ATTRS, keys = "static_pressure", type = FLOAT8)
    STATIC_PRESSURE(ParsedQuery.STATIC_PRESSURE, "StaticPressure"),

    /** The acoustic noise of a fan ({@code 25 dBA}; {@code dB(A)} and a bare {@code dB} are read as dBA). */
    @Unit(symbols = {"dba", "db"}, base = "dBA", display = ValueDisplay.Spaced.class, families = FAN)
    @Source(names = {"noise level", "noise", "acoustic noise", "sound level", "sound pressure level", "noise (dba)"},
            families = FAN)
    @Source(precedence = 9, logic = Described.class, families = FAN)
    @Indexed(column = "noise_dba", type = FLOAT8)
    NOISE(ParsedQuery.NOISE, "Noise"),

    /**
     * The wavelength of an LED in nanometres (dominant, else peak): Mouser {@code Wavelength/Color Temperature}, TME
     * {@code Wavelength}; a range ({@code 620nm~630nm}) is read as its centre.
     */
    @Unit(symbols = "nm", base = "nm", families = LED)
    @Source(names = {"dominant wavelength", "wavelength", "wavelength - dominant", "wavelength/color temperature",
            "peak wavelength", "wavelength - peak", "peak emission wavelength"}, families = LED)
    @Source(precedence = 9, logic = Described.class, families = LED)
    @Indexed(column = Indexed.ATTRS, keys = "wavelength", type = FLOAT8)
    WAVELENGTH(ParsedQuery.WAVELENGTH, "Wavelength"),

    /** The colour temperature of a white LED ({@code 3000 K}): Mouser {@code Color Temperature}, TME {@code Colour temperature}. */
    @Unit(symbols = "k", base = "K", display = ValueDisplay.Spaced.class, families = LED)
    @Source(names = {"colour temperature", "color temperature", "cct", "wavelength/color temperature",
            "color temperature - cct"}, families = LED)
    @Source(precedence = 9, logic = Described.class, families = LED)
    @Indexed(column = Indexed.ATTRS, keys = "colour_temperature", type = FLOAT8)
    COLOUR_TEMPERATURE(ParsedQuery.COLOUR_TEMPERATURE, "ColourTemperature"),

    /**
     * The forward voltage of an LED: Mouser {@code Vf - Forward Voltage}, TME {@code Operating voltage}
     * ({@code 2...2.4V DC}, verified live 2026-10-07); a range is read as its upper end, the most the LED needs; the
     * description's first unlabelled voltage that is not 5V (JLCPCB lists the 5V reverse voltage too).
     */
    @Unit(base = "V", prefixes = {"m", ""})
    @Source(names = {"vf - forward voltage", "forward voltage", "forward voltage (vf)", "voltage - forward (vf) (typ)",
            "vf", "forward voltage typ.", "operating voltage"}, families = LED)
    @Source(precedence = 9, logic = Described.class, families = LED)
    @Indexed(column = Indexed.ATTRS, keys = "forward_voltage", type = FLOAT8)
    FORWARD_VOLTAGE(ParsedQuery.FORWARD_VOLTAGE, "ForwardVoltage"),

    /** The luminous intensity of an LED ({@code 200mcd}, {@code 2.4cd}); a range is read as its upper end. */
    @Unit(symbols = "cd", base = "cd", prefixes = {"m", ""}, families = LED)
    @Source(names = {"luminous intensity", "luminosity", "luminous intensity (iv)", "millicandela rating",
            "luminous intensity iv", "iv"}, families = LED)
    @Source(precedence = 9, logic = Described.class, families = LED)
    @Indexed(column = Indexed.ATTRS, keys = "luminous_intensity", type = FLOAT8)
    LUMINOUS_INTENSITY(ParsedQuery.LUMINOUS_INTENSITY, "LuminousIntensity"),

    /** The luminous flux of an LED ({@code 20lm}). */
    @Unit(symbols = "lm", base = "lm", families = LED)
    @Source(names = {"luminous flux", "luminous flux @ current/temperature", "flux @ 25°c, current - test",
            "luminous flux (typ)"}, families = LED)
    @Source(precedence = 9, logic = Described.class, families = LED)
    @Indexed(column = Indexed.ATTRS, keys = "luminous_flux", type = FLOAT8)
    LUMINOUS_FLUX(ParsedQuery.LUMINOUS_FLUX, "LuminousFlux"),

    /** The viewing angle of an LED ({@code 120°}; {@code 120 deg}, {@code 2θ1/2=120°}). */
    @Unit(symbols = "deg", base = "°", families = LED)
    @Source(names = {"viewing angle", "view angle", "angle of radiation", "viewing angle (2θ1/2)", "beam angle"},
            families = LED)
    @Source(precedence = 9, logic = Described.class, families = LED)
    @Indexed(column = Indexed.ATTRS, keys = "viewing_angle", type = FLOAT8)
    VIEWING_ANGLE(ParsedQuery.VIEWING_ANGLE, "ViewingAngle"),

    /**
     * The operating force of a switch in newton, shown with its gram-force ({@code 1.57 N (160 gf)}): Mouser
     * {@code 160gf}, TME and JLCPCB {@code 1.6N}.
     */
    @Unit(symbols = {"n", "gf"}, factors = {1, 0.00980665}, base = "N", display = ValueDisplay.WithAlternative.class,
            alternative = "gf", families = SWITCH)
    @Source(names = {"operating force", "actuating force", "actuator force", "actuation force", "force",
            "operating force (gf)"}, families = SWITCH)
    @Source(precedence = 9, logic = Described.class, families = SWITCH)
    @Indexed(column = Indexed.ATTRS, keys = "force", type = FLOAT8)
    FORCE(ParsedQuery.FORCE, "Force"),

    /** The mechanical life of a switch in cycles ({@code 100,000 cycles}, {@code 10000 times}, {@code 100k cycles}). */
    @Unit(symbols = {"cycles", "cycle", "times", "operations"}, base = "cycles", display = ValueDisplay.Spaced.class,
            families = SWITCH)
    @Source(names = {"mechanical life", "mechanical durability", "electrical life", "operating life", "life cycle",
            "durability", "life"}, families = SWITCH)
    @Source(precedence = 9, logic = Described.class, families = SWITCH)
    @Indexed(column = Indexed.ATTRS, keys = "life", type = FLOAT8)
    LIFE(ParsedQuery.LIFE, "Life"),

    /** The ingress protection of a switch ({@code IP67}): TME {@code IP rating}, else the description. */
    @Unit(base = "IP", display = ValueDisplay.IpCode.class)
    @Source(names = {"ip rating", "protection class", "ingress protection", "ip code", "ip protection"},
            families = SWITCH, logic = IpCode.class)
    @Source(precedence = 9, families = SWITCH, logic = IpCode.class)
    @Indexed(column = Indexed.ATTRS, keys = "ip_rating", type = FLOAT8)
    IP_RATING(ParsedQuery.IP_RATING, "IpRating"),

    /** The AC voltage rating of a switch: Mouser {@code Voltage Rating AC}, TME {@code 3A/125VAC}, {@code 250VAC}. */
    @Unit(base = "V", prefixes = {"m", "", "k"})
    @Source(names = {"voltage rating ac", "ac voltage rating", "rated voltage ac", "max. voltage ac", "voltage ac"},
            families = SWITCH)
    @Source(precedence = 1, families = SWITCH, logic = SupplyVoltage.class)
    @Indexed(column = Indexed.ATTRS, keys = "voltage_ac", type = FLOAT8)
    VOLTAGE_AC(ParsedQuery.VOLTAGE_AC, "VoltageAC"),

    /** The DC voltage rating of a switch: Mouser {@code Voltage Rating DC}, TME {@code 0.05A/12VDC}, {@code 30VDC}. */
    @Unit(base = "V", prefixes = {"m", "", "k"})
    @Source(names = {"voltage rating dc", "dc voltage rating", "rated voltage dc", "max. voltage dc", "voltage dc"},
            families = SWITCH)
    @Source(precedence = 1, families = SWITCH, logic = SupplyVoltage.class)
    @Indexed(column = Indexed.ATTRS, keys = "voltage_dc", type = FLOAT8)
    VOLTAGE_DC(ParsedQuery.VOLTAGE_DC, "VoltageDC"),

    // ---------------------------------------------------------------- numeric details

    /** The test frequency of a ferrite bead's impedance (Mouser {@code Test Frequency}), read by {@link #IMPEDANCE}. */
    @Source(names = {"test frequency", "impedance test frequency", "frequency", "measuring frequency"})
    TEST_FREQUENCY(ParsedQuery.FREQUENCY, null),

    /** A capacitor's ripple current (TME lists it as {@code Operating current}, Mouser as {@code Ripple Current}). */
    @Source(names = {"ripplecurrent", "ripple current", "rated ripple current", "ripple current (max)",
            "max ripple current", "current - ripple", "ripple current @ high frequency", "ripple current @ low frequency",
            "operating current", "current rating", "rated current", "current"})
    @Source(precedence = 1, names = "ripple", logic = KeyContaining.class)
    RIPPLE_CURRENT(ParsedQuery.CURRENT, "RippleCurrent"),

    // ---------------------------------------------------------------- words

    @Source(names = {"dielectric", "temperature coefficient", "temperature characteristic",
            "temperature characteristics", "dielectric material", "tempco"}, logic = DielectricCode.class)
    @Source(precedence = 9, logic = Described.class)
    DIELECTRIC(null, "Dielectric"),

    /**
     * The package: for an LED its package name in the package field and attributes first ({@link LedPackage}:
     * {@code 5050}, {@code PLCC-4}, {@code 5mm}; TME {@code Case - mm: 5050} before the package field {@code PLCC6}),
     * the package field when KINA recognises it, the inch case codes (Mouser {@code Case Code - in}, TME
     * {@code Case - inch}), the millimetre case codes (TME {@code Case - mm}), the package
     * attributes, the description (for an LED also its package name there, {@link DescribedWord}), the package field
     * as stated, and for a passive the part number ({@link PartNumberPackage}). The vocabulary is the LED one: the
     * other sources name their own.
     */
    @Source(names = {"case - mm", "led diameter", "package / case", "case", "package", "case / package", "lamp size",
            "size"}, families = LED, logic = LedPackage.class)
    @Source(logic = PackageField.class)
    @Source(precedence = 1, names = {"case code - in", "case - inch", "case code (inch)", "package (inch)",
            "imperial size", "case code - inch"}, logic = PackageCode.class)
    @Source(precedence = 2, names = {"case code - mm", "case - mm", "case code (mm)", "metric size", "package (mm)"},
            logic = MetricPackageCode.class)
    @Source(precedence = 3, names = {"package / case", "package/case", "package", "case", "supplier device package",
            "package type", "case / package", "case/package", "housing"}, logic = PackageCode.class)
    @Source(precedence = 4, families = LED, logic = DescribedWord.class)
    @Source(precedence = 4, logic = Described.class)
    @Source(precedence = 5, logic = RawPackageField.class)
    @Source(precedence = 6, logic = PartNumberPackage.class, traits = PASSIVE)
    PACKAGE(null, "Package", Vocabulary.LED_PACKAGE),

    /**
     * SMD or THT; for an LED or a switch also the JLCPCB package field written with a pin count ({@code SMD-4P,6x6mm});
     * the extractor falls back to the category and the package's prefix.
     */
    @Source(names = {"mounting", "mounting style", "mounting type", "mounting method", "termination style", "montage",
            "electrical mounting"}, logic = MountingWord.class)
    @Source(precedence = 1, logic = Described.class)
    @Source(precedence = 2, logic = PackageFieldMounting.class)
    @Source(precedence = 3, families = {LED, SWITCH}, logic = PackageFieldPrefix.class)
    MOUNTING(null, "Mounting"),

    /**
     * Technology parameters: TME {@code Type of resistor} / {@code Type of capacitor} / {@code Type of inductor}
     * (verified live 2026-10-05: thin film, thick film, metal film, carbon film, metal oxide, wire-wound, metal strip;
     * ceramic, tantalum, tantalum-polymer, polymer, electrolytic, polypropylene, polyester, supercapacitor; wire,
     * multilayer, thin film), {@code Kind of capacitor} (MLCC), {@code Kind of resistor} (current shunt, sensing);
     * generic names other sources use. Every name counts: the extractor merges them with the description, category
     * and series.
     */
    @Source(names = {"type of resistor", "type of capacitor", "type of inductor", "kind of capacitor",
            "kind of resistor", "technology", "composition", "construction", "resistor type", "capacitor type",
            "inductor type"})
    TECHNOLOGY(null, "Technology"),

    /** Attributes that state the kind of a semiconductor (TME {@code Type of transistor}, {@code Type of diode}...). */
    @Source(names = {"type of transistor", "type of diode", "kind of voltage regulator", "type of voltage regulator",
            "transistor polarity", "polarity", "channel type", "output type", "regulator type", "transistor type",
            "diode type", "configuration", "number of channels", "technology"})
    SEMICONDUCTOR_TYPE(null, null),

    /** Body dimensions of a crystal or oscillator: its size code when no package names one ({@code 3.2x2.5mm}). */
    @Source(names = {"body dimensions", "dimensions", "size / dimension", "size", "case size", "body size"})
    CRYSTAL_BODY(null, null),

    /** The operating temperature range as printed, normalised ({@code -55...155°C}). */
    @Source(names = {"operating temperature", "operating temperature range", "temperature range"},
            logic = TemperatureRange.class)
    @Source(precedence = 1, logic = DescriptionTemperatureRange.class)
    OPERATING_TEMPERATURE(null, "OperatingTemperature"),

    // ---------------------------------------------------------------- fans (TME parameters verified live 2026-10-07:
    // "Kind of fan" = axial / blower, "Type of fan" = DC, "Fan dimensions" = 40x40x10mm, "Kind of Bearing" = ball /
    // slide / Vapo, "Additional functions" = autorestart, "Signal output" = F type, "Leads" = leads x3; Mouser sends
    // no fan attributes: its category and description say it, as LCSC's do)

    /** The fan type: axial, or radial for a blower or centrifugal fan; a part that says fan and no more is axial. */
    @Source(names = {"kind of fan", "fan type", "type of fan", "product type", "type"}, families = FAN,
            logic = VocabularyWord.class)
    @Source(precedence = 1, families = FAN, logic = DescribedWord.class)
    FAN_TYPE(null, "FanType", Vocabulary.FAN_TYPE),

    /** DC or AC (TME {@code Type of fan} = {@code DC}, {@code Supply voltage} = {@code 12V DC}; Mouser {@code 12VDC}). */
    @Source(names = {"type of fan", "supply voltage", "fan motor", "kind of fan"}, families = FAN,
            logic = VocabularyWord.class)
    @Source(precedence = 1, families = FAN, logic = DescribedWord.class)
    FAN_SUPPLY(null, "FanSupply", Vocabulary.FAN_SUPPLY),

    /** The frame size of a fan ({@code 40x40x10mm}; Mouser {@code 120x38mm} is 120x120x38mm). */
    @Source(names = {"fan dimensions", "frame size", "fan size", "dimensions", "size", "body dimensions"},
            families = FAN, logic = VocabularyWord.class)
    @Source(precedence = 1, families = FAN, logic = DescribedWord.class)
    FRAME_SIZE(null, "FrameSize", Vocabulary.FRAME_SIZE),

    /** The bearing of a fan: ball (dual ball), sleeve (TME {@code slide}), fluid dynamic, rifle, magnetic, vapo. */
    @Source(names = {"kind of bearing", "bearing", "bearing type", "type of bearing"}, families = FAN,
            logic = VocabularyWord.class)
    @Source(precedence = 1, families = FAN, logic = DescribedWord.class)
    BEARING(null, "Bearing", Vocabulary.BEARING),

    /** A fan's features (PWM, tacho, locked rotor, auto restart, 2/3/4-wire, IP rating): every name counts. */
    @Source(names = {"additional functions", "signal output", "leads", "ip rating", "features", "control",
            "output signal"},
            families = FAN, logic = MergedWords.class)
    FAN_FEATURES(null, "Features", Vocabulary.FAN_FEATURES),

    // ---------------------------------------------------------------- LEDs (TME parameters verified live 2026-10-07:
    // "LED colour" = red / white cold / white/blue, "LED lens" = diffused, white / transparent, "Luminosity" =
    // 18...54mcd, "Wavelength" = 631nm, "LED current" = 20mA, "Operating voltage" = 2...2.4V DC, "LED diameter" = 5mm,
    // "Case - mm" = 5050, "LED version" = blinking, "Kind of controller" = WS2811; Mouser's search sends no LED
    // attributes: its category and description say it, as JLCPCB's do)

    /** The colour of an LED's light ({@code red}, {@code warm white}, {@code RGB}); a lens colour is none. */
    @Source(names = {"illumination color", "led colour", "led color", "colour of light", "colour of led",
            "emitted colour", "emitted color", "color", "colour"}, families = LED, logic = VocabularyWord.class)
    @Source(precedence = 1, families = LED, logic = DescribedWord.class)
    COLOUR(null, "LedColour", Vocabulary.LED_COLOUR),

    /** The lens of an LED: {@code clear} (water clear, transparent), {@code diffused} (milky, frosted), {@code tinted}. */
    @Source(names = {"led lens", "lens", "lens color/style", "lens colour", "lens color", "lens type", "lens style",
            "lens transparency"}, families = LED, logic = VocabularyWord.class)
    @Source(precedence = 1, families = LED, logic = DescribedWord.class)
    LENS(null, "LensType", Vocabulary.LENS),

    /**
     * The LED type: addressable (WS2812, SK6812, APA102, {@code Built-in IC}), or no discrete emitter (strip, laser,
     * receiver, display, driver), high power, else an indicator LED: every name, the description and the category
     * together.
     */
    @Source(names = {"led version", "kind of controller", "type of optoelectronic module", "product", "product type",
            "type of led", "kind of led", "led type", "type"}, families = LED, logic = MergedWords.class)
    LED_TYPE(null, "LedType", Vocabulary.LED_TYPE),

    /** An LED's orientation: {@code right angle} (side view), {@code reverse mount}, {@code vertical} (top view). */
    @Source(names = {"orientation", "mounting angle", "emitting direction", "view"}, families = LED,
            logic = VocabularyWord.class)
    @Source(precedence = 1, families = LED, logic = DescribedWord.class)
    LED_ORIENTATION(null, "Orientation", Vocabulary.LED_ORIENTATION),

    // ---------------------------------------------------------------- switches (Mouser categories "Tactile Switches",
    // "Toggle Switches"...; TME "Switch: tactile; SPST-NO; ..."; JLCPCB "Key/Switch" and "Switches" categories)

    /**
     * The switch type ({@code tactile}, {@code toggle}, {@code DIP}...). Read for every family: in a text of another
     * family only a switch IC or a switching sensor ({@code IC}: Mouser {@code Analog Switch ICs}, JLCPCB
     * {@code Power Distribution Switches}; {@code sensor}: Hall, proximity), which no switch request takes.
     */
    @Source(names = {"type of switch", "switch type", "kind of switch", "product", "product type", "type"},
            logic = VocabularyWord.class)
    @Source(precedence = 1, logic = DescribedWord.class)
    SWITCH_TYPE(null, "SwitchType", Vocabulary.SWITCH_TYPE),

    /** The contact configuration ({@code SPDT}, {@code SPST-NO}, {@code 2P2T} is DPDT, {@code 1 Form C} SPDT). */
    @Source(names = {"contacts configuration", "contact form", "contact configuration", "type of contacts",
            "switch configuration", "configuration", "circuit", "contact arrangement"}, families = SWITCH,
            logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    CONTACTS(null, "Contacts", Vocabulary.CONTACTS),

    /** Momentary or latching, or the positions ({@code ON-OFF-ON}): TME {@code Switching method}, Mouser descriptions. */
    @Source(names = {"switching method", "switch function", "function", "switch type", "operation", "actuator type",
            "action", "switching function"}, families = SWITCH, logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    SWITCH_FUNCTION(null, "SwitchFunction", Vocabulary.SWITCH_FUNCTION),

    /**
     * The termination class: {@code PCB} (SMD, THT, PC pins, gull wing), {@code solder lug}, {@code quick connect},
     * {@code wire leads}, {@code screw}, or {@code panel} when only the panel mounting is known: every name, the
     * description and the category together (a solder lug beats a panel mount word).
     */
    @Source(names = {"termination style", "termination", "terminals", "leads", "switch leads", "electrical mounting",
            "mounting style", "mounting", "connection", "type of terminals"}, families = SWITCH,
            logic = MergedWords.class)
    TERMINATION(null, "Termination", Vocabulary.TERMINATION),

    /** The body size of a switch ({@code 6x6x4.3mm}): the attributes, the package field (JLCPCB {@code SMD-4P,6x6mm}). */
    @Source(names = {"dimensions", "body dimensions", "size / dimension", "size", "switch dimensions", "body size"},
            families = SWITCH, logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = PackageFieldWord.class)
    @Source(precedence = 2, families = SWITCH, logic = DescribedWord.class)
    SWITCH_SIZE(null, "SwitchSize", Vocabulary.SWITCH_SIZE),

    /** The panel cut-out of a switch ({@code 12mm}): Mouser {@code Mounting Hole Diameter}, {@code Ø12mm}. */
    @Source(names = {"mounting hole diameter", "mounting hole diam.", "mounting hole dia.", "hole diameter",
            "panel cutout", "panel cut-out", "cut-out"}, families = SWITCH, logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    HOLE_DIAMETER(null, "HoleDiameter", Vocabulary.HOLE_DIAMETER),

    /**
     * The positions of a switch (TME {@code Number of positions}: 2 for ON-ON, 3 for ON-OFF-ON; a rotary switch's
     * positions), or of a DIP switch the number of its switches as the description states it (Mouser
     * {@code 8 Position}, {@code 8POS}).
     */
    @Source(names = {"number of positions", "positions"}, families = SWITCH, logic = FirstInteger.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    SWITCH_POSITIONS(null, "SwitchPositions", Vocabulary.SWITCH_POSITIONS),

    /**
     * The number of switches of a DIP switch (TME {@code Poles number}: 8, where its {@code Number of positions} is the
     * 2 positions of each switch); read for DIP switches before {@link #SWITCH_POSITIONS}.
     */
    @Source(names = {"poles number", "number of switches", "number of sections"}, families = SWITCH,
            logic = FirstInteger.class)
    DIP_SWITCHES(null, null),

    /** {@code yes} for an illuminated switch, {@code no} for one that says it is not (Mouser {@code Non-Illuminated}). */
    @Source(names = {"illuminated", "illumination", "illumination type", "lighting", "backlight", "backlighting"},
            families = SWITCH, logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    ILLUMINATED(null, "Illuminated", Vocabulary.ILLUMINATION),

    /** The colour of a switch's illumination ({@code red}): Mouser {@code Illumination Color}, {@code red LED}. */
    @Source(names = {"illumination color", "illumination colour", "led colour", "led color", "backlight colour",
            "colour of backlight"}, families = SWITCH, logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    ILLUMINATION_COLOUR(null, "IlluminationColour", Vocabulary.ILLUMINATION_COLOUR),

    /** A switch's orientation: {@code right angle} (side actuated, horizontal), {@code vertical} (top actuated). */
    @Source(names = {"orientation", "actuator orientation", "mounting angle", "actuation direction"},
            families = SWITCH, logic = VocabularyWord.class)
    @Source(precedence = 1, families = SWITCH, logic = DescribedWord.class)
    SWITCH_ORIENTATION(null, "Orientation", Vocabulary.SWITCH_ORIENTATION),

    // ---------------------------------------------------------------- passive details

    /** The number of elements of an array or network. */
    @Source(names = {"elements", "number of elements", "number of resistors", "number of capacitors",
            "number of lines", "number of channels", "number of bits"}, traits = ARRAYS, logic = ElementsCount.class)
    ELEMENTS(null, "Elements"),

    /** A capacitor's ESR with its test frequency; the description is read by the extractor (it needs the technology). */
    @Source(names = {"esr", "esr (equivalent series resistance)", "equivalent series resistance", "esr max",
            "esr (max)", "max esr", "esr max."}, logic = OhmsAtFrequency.class)
    @Source(precedence = 1, names = "esr ", logic = OhmsAtKeyPrefix.class)
    ESR(null, "ESR"),

    /** A capacitor's impedance with its test frequency; the description as for {@link #ESR}. */
    @Source(names = {"impedance", "impedance max", "max. impedance", "impedance (max)", "max impedance"},
            logic = OhmsAtFrequency.class)
    @Source(precedence = 1, names = "impedance ", excluding = "tolerance", logic = OhmsAtKeyPrefix.class)
    CAPACITOR_IMPEDANCE(null, "Impedance"),

    /** Body dimensions of a passive: {@code D6.3 x 5.8mm} for a can, {@code 8.8 x 8.4 x 3.8mm} otherwise. */
    @Source(names = {"dimensions", "body dimensions", "size / dimension", "size", "case size", "body size",
            "dimension"}, traits = PASSIVE, logic = Dimensions.class)
    @Source(precedence = 1, traits = PASSIVE, logic = CanDimensions.class)
    @Source(precedence = 2, traits = PASSIVE, logic = PackageFieldDimensions.class)
    @Source(precedence = 3, traits = PASSIVE, logic = DescriptionDimensions.class)
    DIMENSIONS(null, "Dimensions"),

    /** A can's diameter in millimetres, read by {@link #DIMENSIONS}. */
    @Source(names = {"diameter", "body diameter", "case diameter"}, logic = Millimetres.class)
    DIAMETER(null, null),

    /** A can's height in millimetres, read by {@link #DIMENSIONS}. */
    @Source(names = {"height", "body height", "height - seated (max)", "case height", "length"},
            logic = Millimetres.class)
    HEIGHT(null, null),

    // ---------------------------------------------------------------- connectors (TME parameters verified live
    // 2026-10-05: "Type of connector" = pin strips, "Connector" = socket, "Kind of connector" = female, "Number of
    // pins" = 6, "Spatial orientation" = angled 90°, "Contacts pitch" = 2.54mm, "Connector pinout layout" = 1x6,
    // "Electrical mounting" = THT, "Manufacturer series" = XH; Mouser ProductAttributes names as documented by Mouser)

    @Source(names = {"type of connector", "connector type", "connector", "product", "product type", "type"},
            logic = ConnectorTypeWord.class)
    CONNECTOR_TYPE(null, "ConnectorType"),

    /** The gender: one a connector type attribute states, then the gender attributes. */
    @Source(logic = StatedGender.class)
    @Source(precedence = 1, names = {"kind of connector", "gender", "contact gender", "connector gender"},
            logic = GenderWord.class)
    GENDER(null, "Gender"),

    @Source(names = {"number of pins", "number of positions", "positions", "no. of positions", "number of contacts",
            "number of ways", "number of circuits", "pins"}, logic = FirstInteger.class)
    @Source(precedence = 1, logic = LayoutPositions.class)
    POSITIONS(null, "Positions"),

    @Source(names = {"number of rows", "rows", "no. of rows"}, logic = FirstInteger.class)
    @Source(precedence = 1, logic = LayoutRows.class)
    ROWS(null, "Rows"),

    /** The pinout layout ({@code 1x6}, {@code 2x5}), read by {@link #POSITIONS} and {@link #ROWS}. */
    @Source(names = {"connector pinout layout", "pinout layout", "layout"})
    PINOUT_LAYOUT(null, null),

    @Source(names = {"contacts pitch", "pitch", "contact pitch", "pitch - mating", "raster"}, logic = PitchValue.class)
    PITCH(null, "Pitch"),

    @Source(names = {"spatial orientation", "mounting angle", "orientation", "angle", "termination orientation"},
            logic = ConnectorOrientation.class)
    ORIENTATION(null, "Orientation"),

    @Source(names = {"manufacturer series", "series"}, logic = ShortSeries.class)
    SERIES(null, "Series"),

    /**
     * TME parameters that carry USB details (verified live 2026-10-05: {@code Version} = USB 2.0 / USB 3.1 Gen 2 /
     * USB 4.0, {@code Data transfer rate} = 5Gbps, {@code Connector variant} = middle board mount / Gen.2x2 / sealed,
     * {@code Connectors application} = only for charging (6p), {@code IP rating} = IP67, {@code Electrical mounting}
     * = hybrid SMT/THT). Mouser's keyword search returns no such ProductAttributes for USB connectors (only
     * Packaging and Standard Pack Qty) and LCSC has no parameter columns, so both rely on the description.
     */
    @Source(names = {"type of connector", "version", "usb version", "usb standard", "data transfer rate", "data rate",
            "connector variant", "connectors application", "ip rating", "ingress protection", "electrical mounting",
            "mounting style"})
    USB_DETAILS(null, null),

    /** The data rate of a USB connector (TME {@code Data transfer rate}): it beats the {@code Version}. */
    @Source(names = {"data transfer rate", "data rate"})
    USB_RATE(null, null);

    /** The numeric attributes the extractor reports, in output order. */
    public static final List<PartAttribute> VALUES = List.of(CAPACITANCE, RESISTANCE, INDUCTANCE, IMPEDANCE,
            FREQUENCY, VOLTAGE, CURRENT, SATURATION_CURRENT, DCR, POWER, TEMPERATURE, LIFETIME, TOLERANCE, SPEED,
            AIRFLOW, STATIC_PRESSURE, NOISE, WAVELENGTH, COLOUR_TEMPERATURE, FORWARD_VOLTAGE, LUMINOUS_INTENSITY,
            LUMINOUS_FLUX, VIEWING_ANGLE, FORCE, LIFE, IP_RATING, VOLTAGE_AC, VOLTAGE_DC);

    private final String kind;
    private final String key;
    private final Vocabulary vocabulary;

    PartAttribute(String kind, String key) {
        this(kind, key, null);
    }

    PartAttribute(String kind, String key, Vocabulary vocabulary) {
        this.kind = kind;
        this.key = key;
        this.vocabulary = vocabulary;
    }

    /** The vocabulary a word attribute is read in ({@code VocabularyWord}, {@code DescribedWord}), else null. */
    public Vocabulary vocabulary() {
        return vocabulary;
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
     * Where the field index stores the attribute's SI value (DESIGN.md 3.8): a typed column or an {@code attrs} key;
     * null for an attribute the index does not store by itself.
     */
    public Indexed indexed() {
        return Declarations.INDEXED.get(this);
    }

    /** The numeric attribute of a {@link ParsedQuery} value kind that the field index stores, null when none. */
    public static PartAttribute indexedOf(String kind) {
        if (kind == null) {
            return null;
        }
        for (PartAttribute a : VALUES) {
            if (kind.equals(a.kind) && a.indexed() != null) {
                return a;
            }
        }
        return null;
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

    /**
     * The display form of a value of a {@link ParsedQuery} kind in its declared unit ({@code 10uF}, {@code 250W}); a
     * kind without a unit is displayed as a frequency.
     */
    public static String display(String kind, double value) {
        Unit unit = unitOrFrequency(kind);
        return Declarations.display(unit).display(value, unit);
    }

    /** As {@link #display(String, double)}, with the test condition when not null ({@code 120ohm @100MHz}). */
    public static String display(String kind, double value, Double condition) {
        Unit unit = unitOrFrequency(kind);
        ValueDisplay display = Declarations.display(unit);
        return display.display(value, unit) + (condition == null ? "" : display.condition(condition));
    }

    private static Unit unitOrFrequency(String kind) {
        Unit unit = unitOf(kind);
        return unit != null ? unit : FREQUENCY.unit();
    }

    /** The {@link ParsedQuery} kind of each unit symbol ({@code "vdc"} -&gt; voltage), in declaration order. */
    public static Map<String, String> unitSymbols() {
        return Declarations.SYMBOLS;
    }

    /** The base units of one {@code symbol} ({@link Unit#factors()}): 1.699011 for {@code cfm}, 1 for most. */
    public static double symbolFactor(String symbol) {
        return Declarations.FACTORS.getOrDefault(symbol, 1.0);
    }

    /**
     * The labels of the families whose texts read {@code symbol} ({@link Unit#families()}); empty when every text
     * does.
     */
    public static java.util.Set<String> symbolFamilies(String symbol) {
        return Declarations.SYMBOL_FAMILIES.getOrDefault(symbol, java.util.Set.of());
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
        private static final Map<Class<? extends ValueDisplay>, ValueDisplay> DISPLAYS = new ConcurrentHashMap<>();
        static final Map<PartAttribute, Unit> UNITS = new EnumMap<>(PartAttribute.class);
        static final Map<PartAttribute, Indexed> INDEXED = new EnumMap<>(PartAttribute.class);
        static final Map<PartAttribute, List<Declared>> SOURCES = new EnumMap<>(PartAttribute.class);
        static final Map<String, String> SYMBOLS;
        static final Map<String, Double> FACTORS = new java.util.HashMap<>();
        static final Map<String, java.util.Set<String>> SYMBOL_FAMILIES = new java.util.HashMap<>();

        static {
            Map<String, String> symbols = new LinkedHashMap<>();
            for (PartAttribute a : values()) {
                Field field = field(a);
                Unit unit = field.getAnnotation(Unit.class);
                Indexed indexed = field.getAnnotation(Indexed.class);
                if (indexed != null) {
                    if (a.kind == null || indexed.predicate() != Indexed.Predicate.NONE || indexed.javaOnly()
                            || !indexed.condition().isEmpty() && Indexed.ATTRS.equals(indexed.column())) {
                        throw new IllegalStateException(a + ": @Indexed on an attribute declares a column of a value"
                                + " (and a typed column of its condition)");
                    }
                    INDEXED.put(a, indexed);
                }
                if (unit != null) {
                    UNITS.put(a, unit);
                    if (unit.factors().length > 0 && unit.factors().length != unit.symbols().length) {
                        throw new IllegalStateException(a + ": one factor per unit symbol");
                    }
                    java.util.Set<String> families = Arrays.stream(unit.families()).map(ComponentFamily::label)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    for (int i = 0; i < unit.symbols().length; i++) {
                        String symbol = unit.symbols()[i];
                        if (symbols.putIfAbsent(symbol, a.kind) != null) {
                            throw new IllegalStateException("unit symbol declared twice: " + symbol);
                        }
                        if (unit.factors().length > 0) {
                            FACTORS.put(symbol, unit.factors()[i]);
                        }
                        if (!families.isEmpty()) {
                            SYMBOL_FAMILIES.put(symbol, families);
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

        static ValueDisplay display(Unit unit) {
            return DISPLAYS.computeIfAbsent(unit.display(), Declarations::instance);
        }

        private static <T> T instance(Class<? extends T> type) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException
                     | InvocationTargetException e) {
                throw new IllegalStateException(type.getName() + " needs a public no-arg constructor", e);
            }
        }
    }
}
