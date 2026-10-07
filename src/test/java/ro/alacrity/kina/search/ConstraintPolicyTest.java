package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.ConstraintKind;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * The never-relax rules (DESIGN.md 3.4 "Hard constraints", user decision 2026-10-07): hard constraints per family,
 * the relaxable ones, the type distinctions, the imperial package rule and the configuration override.
 */
class ConstraintPolicyTest {

    private final QueryParser parser = new QueryParser();
    private final ParametricExtractor extractor = new ParametricExtractor();
    private final DeterministicRanker ranker = new DeterministicRanker(extractor);
    private final ConstraintPolicy policy = ConstraintPolicy.DEFAULTS;

    private List<String> conflicts(String query, Part part) {
        return policy.check(parser.parse(query), extractor.features(part)).conflicts();
    }

    private List<String> mismatches(String query, Part part) {
        return ranker.assess(parser.parse(query), part).mismatches();
    }

    private static Part lcsc(String number, String description, String category, String pkg) {
        return RankingFixtures.lcsc(number, "ACME", number, description, category, pkg, Map.of());
    }

    private static Part tme(String symbol, String description, String category, Map<String, String> attributes) {
        return RankingFixtures.tme(symbol, "ACME", description, category, null, attributes);
    }

    private static Part mouser(String mpn, String description, String category, String pkg) {
        return RankingFixtures.mouser(mpn, "ACME", description, category, pkg, Map.of());
    }

    // ---------------------------------------------------------------- the table

    /** The table read from the {@code @Relax} declarations equals the decided one (0.5.0, technology hard for transistors since 2026-10-07). */
    @Test
    void theDeclaredTableIsTheDecidedOne() {
        Map<String, List<String>> decided = new java.util.LinkedHashMap<>();
        decided.put("resistor", List.of("type", "value", "package", "mounting", "technology", "elements",
                "form factor"));
        decided.put("capacitor", List.of("type", "value", "package", "mounting", "technology", "elements",
                "form factor"));
        decided.put("inductor", List.of("type", "value", "mounting", "technology", "form factor"));
        decided.put("ferrite", List.of("type", "value", "package", "mounting", "elements"));
        decided.put("crystal", List.of("type", "value", "load capacitance", "mounting"));
        decided.put("oscillator", List.of("type", "value", "mounting"));
        decided.put("diode", List.of("type", "voltage", "package", "mounting"));
        decided.put("transistor", List.of("type", "polarity", "package", "mounting", "technology"));
        decided.put("regulator", List.of("type", "voltage", "package", "mounting"));
        decided.put("connector", List.of("type", "connector type", "gender", "positions", "pitch", "package",
                "mounting"));
        decided.put("usb", List.of("type", "usb type", "pin configuration", "usb standard", "gender", "mounting"));
        decided.put("default", List.of("type", "value", "package", "mounting", "technology", "elements", "polarity",
                "voltage", "form factor"));
        assertThat(ConstraintPolicy.DEFAULT_HARD.keySet()).containsExactlyElementsOf(decided.keySet());
        decided.forEach((family, names) -> {
            assertThat(ConstraintPolicy.DEFAULT_HARD.get(family)).as(family).containsExactlyInAnyOrderElementsOf(names);
            assertThat(policy.table().get(family)).as(family).isEqualTo(java.util.Set.copyOf(names));
        });
        assertThat(ConstraintPolicy.RELAXABLE).containsExactly("dielectric", "package", "tolerance", "orientation",
                "tcr", "esr", "dcr");
        assertThat(ConstraintPolicy.NAMES).containsExactlyInAnyOrder("value", "package", "mounting", "technology",
                "elements", "type", "polarity", "voltage", "load capacitance", "connector type", "gender",
                "positions", "pitch", "usb type", "pin configuration", "usb standard", "form factor", "dielectric",
                "tolerance", "orientation", "tcr", "esr", "dcr");
    }

    @Test
    void theDecidedTable() {
        Map<String, List<String>> t = ConstraintPolicy.DEFAULT_HARD;
        // the primary value, mounting and the type are hard for every component family
        for (String family : List.of("resistor", "capacitor", "inductor", "ferrite", "crystal", "oscillator")) {
            assertThat(t.get(family)).as(family).contains("value", "mounting", "type");
        }
        // the package is hard, except for inductors, crystals and oscillators
        for (String family : List.of("resistor", "capacitor", "ferrite", "diode", "transistor", "regulator",
                "connector", "default")) {
            assertThat(t.get(family)).as(family).contains("package");
        }
        for (String family : List.of("inductor", "crystal", "oscillator")) {
            assertThat(t.get(family)).as(family).doesNotContain("package");
        }
        assertThat(t.get("crystal")).contains("load capacitance");
        assertThat(t.get("diode")).contains("type", "voltage");
        assertThat(t.get("transistor")).contains("type", "polarity");
        assertThat(t.get("regulator")).contains("type", "voltage");
        assertThat(t.get("connector")).contains("connector type", "gender", "positions", "pitch")
                .doesNotContain("orientation");
        assertThat(t.get("usb")).contains("usb type", "pin configuration", "usb standard")
                .doesNotContain("orientation");
        // dielectric, tolerance, orientation, TCR, ESR and DCR preferences are relaxable everywhere
        t.values().forEach(hard -> assertThat(hard).doesNotContain("dielectric", "tolerance", "orientation", "tcr",
                "esr", "dcr"));
        assertThat(policy.isRelaxable(parser.parse("10uH inductor 0805"), "package")).isTrue();
        assertThat(policy.isRelaxable(parser.parse("16MHz crystal 3225"), "package")).isTrue();
        assertThat(policy.isRelaxable(parser.parse("22uF X7R 1206 MLCC"), "package")).isFalse();
        assertThat(policy.isRelaxable(parser.parse("22uF X7R 1206 MLCC"), "dielectric")).isTrue();
        // a rating is never relaxable
        assertThat(policy.isRelaxable(parser.parse("22uF X7R 1206 25V MLCC"), "voltage")).isFalse();
    }

    // ---------------------------------------------------------------- passives

    @Test
    void capacitorValueAndPackageAreHardTheDielectricIsNot() {
        String q = "22uF X7R 1206 25V MLCC";
        assertThat(conflicts(q, lcsc("A", "25V 10uF X7R ±10% 1206", "Capacitors / Multilayer Ceramic Capacitors MLCC "
                + "- SMD/SMT", "1206"))).containsExactly("capacitance");
        assertThat(conflicts(q, lcsc("B", "25V 22uF X7R ±10% 1210", "Capacitors / Multilayer Ceramic Capacitors MLCC "
                + "- SMD/SMT", "1210"))).containsExactly("package");
        Part x5r = lcsc("C", "25V 22uF X5R ±10% 1206", "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT",
                "1206");
        assertThat(conflicts(q, x5r)).isEmpty();
        assertThat(mismatches(q, x5r)).containsExactly("dielectric: X5R instead of X7R");
    }

    @Test
    void resistorValueIsHardALooserToleranceIsNot() {
        String q = "10k 1% 0603 resistor";
        assertThat(conflicts(q, lcsc("A", "100mW 4.7kΩ ±1% 0603", "Resistors / Chip Resistor - Surface Mount",
                "0603"))).containsExactly("resistance");
        Part loose = lcsc("B", "100mW 10kΩ ±5% 0603", "Resistors / Chip Resistor - Surface Mount", "0603");
        assertThat(conflicts(q, loose)).isEmpty();
        assertThat(mismatches(q, loose)).containsExactly("tolerance: 5% instead of 1%");
        assertThat(conflicts(q, lcsc("C", "100mW 10kΩ ±1% 0805", "Resistors / Chip Resistor - Surface Mount",
                "0805"))).containsExactly("package");
    }

    @Test
    void inductorPackageIsRelaxableItsInductanceIsNot() {
        String q = "10uH inductor 0805";
        Part bigger = lcsc("A", "10uH ±20% 1A 1210", "Inductors/Coils/Transformers / Inductors (SMD)", "1210");
        assertThat(conflicts(q, bigger)).isEmpty();
        assertThat(mismatches(q, bigger)).containsExactly("package: 1210 instead of 0805");
        assertThat(conflicts(q, lcsc("B", "4.7uH ±20% 1A 0805", "Inductors/Coils/Transformers / Inductors (SMD)",
                "0805"))).containsExactly("inductance");
    }

    @Test
    void ferriteImpedanceAtItsFrequencyAndPackageAreHard() {
        String q = "120 ohm 100MHz 0805 ferrite bead";
        assertThat(conflicts(q, tme("CORE", "Ferrite: bead; 120Ω; SMD; 0805", "Ferrite beads",
                attrs("Impedance at 25MHz", "120Ω")))).containsExactly("impedance");
        assertThat(conflicts(q, tme("BEAD", "Ferrite: bead; 120Ω; SMD; 1206", "Ferrite beads",
                attrs("Impedance at 100MHz", "120Ω")))).containsExactly("package");
    }

    // ---------------------------------------------------------------- crystals and oscillators

    @Test
    void crystalsAndOscillatorsAreNeverMixed() {
        // JLCPCB calls a passive crystal "Crystal Oscillator" in its description; the category decides
        Part crystal = lcsc("C2981650", "-40℃~+85℃ 10pF 16MHz Crystal Oscillator ±10ppm",
                "Crystals, Oscillators, Resonators / Crystals", "SMD3225-4P");
        Part oscillator = lcsc("C7504316", "-40℃~+85℃ 1.62V~3.63V 1.8mA 16MHz CMOS ±50ppm",
                "Crystals, Oscillators, Resonators / Crystal Oscillators", "SMD3225-4P");
        Part tcxo = lcsc("C54333745", "-40℃~+85℃ 1.5mA 16MHz Clipped sine wave ±0.5ppm",
                "Crystals, Oscillators, Resonators / Temperature Compensated Crystal Oscillators (TCXO)", "SMD2016-4P");
        // TME files both under "Resonators and Generators": the description decides
        Part tmeCrystal = tme("CX3225", "Crystal; 16MHz; SMD; 3.2x2.5x0.8mm; 8pF", "Resonators and Generators",
                attrs("Frequency", "16MHz", "Body dimensions", "3.2x2.5x0.8mm", "Mounting", "SMD"));
        Part tmeOscillator = tme("W9M", "Generator: quartz; 16MHz; 16pF; ±30ppm; 12.7x4.8x3.8mm",
                "Resonators and Generators", attrs("Frequency", "16MHz", "Type of generator", "quartz"));
        Part mouserCrystal = mouser("ABM8", "Crystals Xtal 3225 4-SMD 16MHz 7pF", "Crystals", null);
        Part mouserMems = mouser("ASEMB", "MEMS Oscillators Oscillator 3225 4-SMD 16MHz CMOS", "MEMS Oscillators",
                null);

        String crystalQuery = "16MHz crystal 3225 SMD";
        for (Part p : List.of(crystal, tmeCrystal, mouserCrystal)) {
            assertThat(conflicts(crystalQuery, p)).as(p.distributorPartNumber()).isEmpty();
        }
        for (Part p : List.of(oscillator, tcxo, tmeOscillator, mouserMems)) {
            assertThat(conflicts(crystalQuery, p)).as(p.distributorPartNumber()).containsExactly("type");
        }
        String oscillatorQuery = "16MHz oscillator 3225";
        for (Part p : List.of(oscillator, tcxo, tmeOscillator, mouserMems)) {
            assertThat(conflicts(oscillatorQuery, p)).as(p.distributorPartNumber()).isEmpty();
        }
        for (Part p : List.of(crystal, tmeCrystal, mouserCrystal)) {
            assertThat(conflicts(oscillatorQuery, p)).as(p.distributorPartNumber()).containsExactly("type");
        }
        assertThat(parser.parse("16MHz TCXO").family()).isEqualTo("oscillator");
        assertThat(parser.parse("crystal oscillator 16MHz").family()).isEqualTo("oscillator");
        assertThat(parser.parse("16MHz resonator").family()).isEqualTo("crystal");
        // TME states the body only: 3.2 x 2.5 mm is the 3225 size
        assertThat(extractor.extract(tmeCrystal)).containsEntry("Package", "3225").containsEntry("Family", "crystal");
        // the package of a crystal is relaxable; the TCXO's 2016 is a mismatch, not an exclusion
        assertThat(conflicts(oscillatorQuery, tcxo)).isEmpty();
        assertThat(mismatches(oscillatorQuery, tcxo)).containsExactly("package: 2016 instead of 3225");
    }

    @Test
    void crystalLoadCapacitanceIsExactAndTheFrequencyIsThePrimaryValue() {
        ParsedQuery q = parser.parse("16MHz crystal 10pF 3225");
        assertThat(ConstraintKind.primaryKind(q)).isEqualTo(ParsedQuery.FREQUENCY);
        Part ten = lcsc("A", "-40℃~+85℃ 10pF 16MHz ±10ppm", "Crystals, Oscillators, Resonators / Crystals",
                "SMD3225-4P");
        Part twenty = lcsc("B", "-40℃~+85℃ 20pF 16MHz ±10ppm", "Crystals, Oscillators, Resonators / Crystals",
                "SMD3225-4P");
        Part twelveMhz = lcsc("C", "-40℃~+85℃ 10pF 12MHz ±10ppm", "Crystals, Oscillators, Resonators / Crystals",
                "SMD3225-4P");
        assertThat(conflicts(q.originalText(), ten)).isEmpty();
        assertThat(conflicts(q.originalText(), twenty)).containsExactly("load capacitance");
        assertThat(conflicts(q.originalText(), twelveMhz)).containsExactly("frequency");
    }

    // ---------------------------------------------------------------- semiconductors

    @Test
    void diodeTypesAreHard() {
        String schottky = "Schottky diode 40V 3A SMA";
        Part tmeSchottky = tme("B340A", "Diode: Schottky rectifying; SMA; SMD; 40V; 3A", "SMD Schottky diodes",
                attrs("Type of diode", "Schottky rectifying", "Case", "SMA"));
        Part tmeRectifier = tme("S3A", "Diode: rectifying; SMA; SMD; 50V; 3A", "SMD universal diodes",
                attrs("Type of diode", "rectifying", "Case", "SMA"));
        Part lcscGeneral = lcsc("C4154412", "-50℃~+150℃ 1.1V@2A 2A 5uA 60A 800V Independent",
                "Diodes / Diodes - General Purpose", "SMA(DO-214AC)");
        Part lcscSwitching = lcsc("C2836098", "1.6ns 150mW 300mA 500nA@80V 80V", "Diodes / Switching Diodes", "SMA");
        Part mouserRectifier = mouser("S3B", "Rectifiers 100V 3A Standard Recovery", "Rectifiers", "SMA");
        Part zener = lcsc("Z", "500mW 5.1V", "Diodes / Zener Diodes", "SMA");
        Part tvs = lcsc("T", "400W 40V TVS Unidirectional", "Circuit Protection / TVS", "SMA");
        assertThat(conflicts(schottky, tmeSchottky)).isEmpty();
        for (Part p : List.of(tmeRectifier, lcscGeneral, lcscSwitching, mouserRectifier, zener, tvs)) {
            assertThat(conflicts(schottky, p)).as(p.distributorPartNumber()).containsExactly("type");
        }
        // and the other way: a rectifier request never returns a Schottky diode
        ParsedQuery rectifier = parser.parse("rectifier diode 1A SMA");
        assertThat(rectifier.subtype()).isEqualTo("standard");
        assertThat(conflicts(rectifier.originalText(), tmeSchottky)).containsExactly("type");
        assertThat(conflicts(rectifier.originalText(), tmeRectifier)).isEmpty();
        // a plain "diode" request accepts every kind
        assertThat(conflicts("diode SMA", tmeSchottky)).isEmpty();
        // Zener and TVS are different families
        assertThat(conflicts("TVS diode 40V SMA", zener)).containsExactly("type");
    }

    @Test
    void zenerVoltageIsExact() {
        // JLCPCB lists the values sorted as text, the range first: every single voltage is a candidate
        Part z27 = lcsc("C6136767", "1 Independent 1.3W 25.1V~28.9V 27V 30Ω 500uA@20V 750Ω", "Diodes / Zener Diodes",
                "DO-41");
        assertThat(conflicts("Zener 27V DO-41", z27)).isEmpty();
        assertThat(conflicts("Zener 24V DO-41", z27)).containsExactly("voltage");
    }

    @Test
    void transistorPolarityIsHard() {
        String q = "SOT-23 N-channel MOSFET 30V";
        assertThat(parser.parse(q).polarity()).isEqualTo("N-channel");
        Part n = tme("AO3402", "Transistor: N-MOSFET; unipolar; 30V; 4A; SOT23", "SMD N channel transistors",
                attrs("Type of transistor", "N-MOSFET", "Case", "SOT23"));
        Part p = tme("AO3409", "Transistor: P-MOSFET; unipolar; -30V; -2.6A; SOT23", "SMD P channel transistors",
                attrs("Type of transistor", "P-MOSFET", "Case", "SOT23"));
        Part lcscP = lcsc("C48971735", "-55℃~+150℃ 1 P-Channel 1.6V 350mW 3A 40V 53pF P-Channel",
                "Transistors/Thyristors / MOSFETs", "SOT-23");
        Part mouserN = mouser("2N7002", "MOSFETs N-CH 60V 300mA", "MOSFETs", "SOT-23-3");
        Part pair = lcsc("PAIR", "1 N-channel + 1 P-Channel 30V 4A", "Transistors/Thyristors / MOSFETs", "SOT-23-6");
        Part bjt = lcsc("BC847", "45V 100mA NPN Bipolar (BJT)", "Transistors / Bipolar Transistors - BJT", "SOT-23");
        assertThat(conflicts(q, n)).isEmpty();
        assertThat(conflicts(q, mouserN)).isEmpty();
        assertThat(conflicts(q, p)).containsExactly("polarity");
        assertThat(conflicts(q, lcscP)).containsExactly("polarity");
        assertThat(conflicts(q, pair)).contains("polarity");
        assertThat(conflicts(q, bjt)).containsExactly("polarity");
        assertThat(extractor.extract(p)).containsEntry("Polarity", "P-channel");
        assertThat(conflicts("NPN transistor SOT-23", bjt)).isEmpty();
        assertThat(conflicts("PNP transistor SOT-23", bjt)).containsExactly("polarity");
    }

    @Test
    void regulatorsFixedVsAdjustableAndTheExactOutputVoltage() {
        ParsedQuery q = parser.parse("3.3V LDO SOT-223");
        assertThat(q.subtype()).isEqualTo("fixed");
        Part fixed = tme("LD1117AS33", "IC: voltage regulator; LDO,linear,fixed; 3.3V; 1A; SOT223; SMD",
                "LDO fixed voltage regulators", attrs("Kind of voltage regulator", "fixed, LDO, linear",
                        "Output voltage", "3.3V", "Input voltage", "4.75...10V", "Case", "SOT223"));
        Part adjustable = tme("LD1117S", "IC: voltage regulator; LDO,linear,adjustable; 1.25...15V; 1A; SOT223",
                "LDO adjustable voltage regulators", attrs("Kind of voltage regulator", "adjustable, LDO, linear",
                        "Case", "SOT223"));
        Part five = tme("LD1117S50", "IC: voltage regulator; LDO,linear,fixed; 5V; 1A; SOT223; SMD",
                "LDO fixed voltage regulators", attrs("Kind of voltage regulator", "fixed, LDO, linear",
                        "Output voltage", "5V", "Case", "SOT223"));
        // JLCPCB: the 15 V input maximum sorts before the 3.3 V output
        Part ams = lcsc("C6186", "-40℃~+125℃ 0.003%Vout 1 1.1V@(800mA) 15V 1A 3.3V 5mA 72dB@(120Hz) Fixed "
                + "Positive", "Power Management ICs / Linear Voltage Regulators (LDO)", "SOT-223");
        assertThat(conflicts(q.originalText(), fixed)).isEmpty();
        assertThat(conflicts(q.originalText(), ams)).isEmpty();
        assertThat(conflicts(q.originalText(), adjustable)).contains("type");
        assertThat(conflicts(q.originalText(), five)).containsExactly("voltage");
        assertThat(extractor.extract(adjustable)).containsEntry("Subtype", "adjustable");
        assertThat(conflicts("adjustable LDO SOT-223", fixed)).containsExactly("type");
    }

    // ---------------------------------------------------------------- connectors

    @Test
    void connectorTypeGenderPositionsAndPitchAreHardTheOrientationIsNot() {
        String q = "female header 1x6 2.54mm right angle";
        Part straight = tme("ZL262-6SG", "Socket; pin strips; female; PIN: 6; straight; 2.54mm; THT; 1x6",
                "Pin headers", attrs("Kind of connector", "female", "Number of pins", "6",
                        "Spatial orientation", "straight", "Contacts pitch", "2.54mm"));
        Part male = tme("ZL201-6G", "Pin header; pin strips; male; PIN: 6; angled 90°; 2.54mm; THT; 1x6",
                "Pin headers", attrs("Kind of connector", "male", "Number of pins", "6",
                        "Spatial orientation", "angled 90°", "Contacts pitch", "2.54mm"));
        Part eight = tme("ZL262-8SG", "Socket; pin strips; female; PIN: 8; angled 90°; 2.54mm; THT; 1x8",
                "Pin headers", attrs("Kind of connector", "female", "Number of pins", "8",
                        "Spatial orientation", "angled 90°", "Contacts pitch", "2.54mm"));
        Part fine = tme("ZL305-6SG", "Socket; pin strips; female; PIN: 6; angled 90°; 2mm; THT; 1x6",
                "Pin headers", attrs("Kind of connector", "female", "Number of pins", "6",
                        "Spatial orientation", "angled 90°", "Contacts pitch", "2mm"));
        assertThat(conflicts(q, straight)).isEmpty();
        assertThat(mismatches(q, straight)).containsExactly("orientation: vertical instead of right angle");
        assertThat(conflicts(q, male)).contains("gender");
        assertThat(conflicts(q, eight)).containsExactly("positions");
        assertThat(conflicts(q, fine)).containsExactly("pitch");
    }

    @Test
    void theConnectorCoreLoosensOnlyTheOrientation() {
        ParsedQuery q = parser.parse("female header 1x6 2.54mm right angle");
        String sent = DistributorPhraser.phrase(Distributor.TME, q);
        // TME's core is the type words with the positions: the orientation goes (relaxable), the pitch and the gender
        // stay hard in the ranker
        assertThat(DistributorPhraser.ladder(Distributor.TME, q, sent)).last()
                .satisfies(step -> assertThat(step.relaxed()).containsExactly("orientation"));
        ConstraintPolicy strict = ConstraintPolicy.from(RankingFixtures.properties("kina.search.hard-constraints"
                + ".connector", "connector type,gender,positions,pitch,orientation").search());
        assertThat(DistributorPhraser.ladder(Distributor.TME, q, sent, strict)).last()
                .satisfies(step -> assertThat(step.relaxed()).isEmpty());
    }

    @Test
    void usbTypeAndStatedPinConfigurationAreHardTheStandardMayBeExceeded() {
        Part c16 = lcsc("C16", "USB 2.0 Type-C 16P Female SMD", "Connectors / USB Connectors", "SMD");
        Part c24 = lcsc("C24", "USB 3.1 Type-C 24P Female SMD", "Connectors / USB Connectors", "SMD");
        Part microB = lcsc("MB", "USB 2.0 Micro-B 5P Female SMD", "Connectors / USB Connectors", "SMD");
        assertThat(conflicts("USB-C receptacle 16 pin SMD", c16)).isEmpty();
        assertThat(conflicts("USB-C receptacle 16 pin SMD", c24)).containsExactly("pin configuration");
        assertThat(conflicts("USB-C receptacle 16 pin SMD", microB)).contains("usb type");
        // a USB 2.0 request implies 16 pins only for ranking; a 24-pin USB 3.1 part exceeds the standard
        assertThat(conflicts("USB-C receptacle USB 2.0 SMD", c24)).isEmpty();
        // a lower standard is excluded
        assertThat(conflicts("USB-C receptacle USB 3.1 SMD", c16)).containsExactly("usb standard");
    }

    // ---------------------------------------------------------------- packages: imperial always

    @Test
    void packagesAreImperialEverywhere() {
        assertThat(Recognizers.imperial("0603 (1608 metric)")).isEqualTo("0603 (0603)");
        assertThat(Recognizers.imperial("3216M")).isEqualTo("1206");
        assertThat(Recognizers.imperial("0603mm")).isEqualTo("0201");
        assertThat(Recognizers.imperial("0402 mm")).isEqualTo("01005");
        assertThat(Recognizers.imperial("P=2.54mm 0603")).isEqualTo("P=2.54mm 0603");
        // a bare 0603 is imperial 0603 in a query, a description and a package field
        assertThat(parser.parse("100nF 0603 X7R").packageName()).isEqualTo("0603");
        assertThat(extractor.extract(lcsc("A", "50V 100nF X7R ±10% 0603", "Capacitors / Multilayer Ceramic "
                + "Capacitors MLCC - SMD/SMT", "0603"))).containsEntry("Package", "0603");
        // TME Case - mm is metric: 0603 mm is imperial 0201
        Part tmeMetric = tme("X", "Capacitor: ceramic; MLCC; 1pF; 25V; C0G", "MLCC SMD capacitors",
                attrs("Case - mm", "0603"));
        assertThat(extractor.extract(tmeMetric)).containsEntry("Package", "0201");
        Part tmeLabelled = RankingFixtures.tme("Y", "ACME", "Capacitor: ceramic; MLCC; 1uF", "MLCC SMD capacitors",
                "1608 mm", attrs("Case - mm", "1608"));
        assertThat(extractor.extract(tmeLabelled)).containsEntry("Package", "0603");
        // Mouser writes the imperial code with the metric one in brackets
        assertThat(extractor.extract(mouser("GRM033", "Multilayer Ceramic Capacitors MLCC - SMD/SMT 1pF 25V C0G",
                "Ceramic Capacitors", "0201 (0603 metric)"))).containsEntry("Package", "0201");
        // so a 0603 request excludes the 0201 part
        assertThat(conflicts("1pF C0G 0603", tmeMetric)).containsExactly("package");
        // the phrase carries the imperial code
        assertThat(DistributorPhraser.phrase(Distributor.TME, parser.parse("10uF 2012 metric X7R 25V")))
                .isEqualTo("10uF 0805 X7R");
    }

    @Test
    void canCapacitorsMatchOnTheirSizeWithinRounding() {
        ParsedQuery q = parser.parse("100uF 16V aluminium electrolytic capacitor 6.3x5.4mm SMD");
        assertThat(q.packageName()).isEqualTo("D6.3 x 5.4mm");
        assertThat(q.keywords()).doesNotContain("6.3x5.4mm");
        Part close = lcsc("A", "100uF 16V ±20% SMD,D6.3xL5.5mm", "Capacitors / Aluminum Electrolytic Capacitors - SMD",
                "SMD,D6.3xL5.5mm");
        Part tall = lcsc("B", "100uF 16V ±20% SMD,D6.3xL7.7mm", "Capacitors / Aluminum Electrolytic Capacitors - SMD",
                "SMD,D6.3xL7.7mm");
        assertThat(conflicts(q.originalText(), close)).isEmpty();
        assertThat(conflicts(q.originalText(), tall)).containsExactly("package");
        assertThat(Recognizers.samePackage("D6.3 x 5.4mm", "D6.5 x 5.6mm")).isTrue();
        assertThat(Recognizers.samePackage("D6.3 x 5.4mm", "D8 x 5.4mm")).isFalse();
    }

    @Test
    void aPackageKinaCannotReadNeverExcludes() {
        Part odd = lcsc("C1", "-55℃~+150℃ 1 N-channel 30V 10A", "Transistors/Thyristors / MOSFETs", "PowerFLAT");
        assertThat(conflicts("N-channel MOSFET SOT-23", odd)).isEmpty();
        assertThat(ranker.assess(parser.parse("N-channel MOSFET SOT-23"), odd).unverified()).contains("package");
        // JLCPCB's lead-count marker and TO-236AB are SOT-23
        assertThat(Recognizers.samePackage("SOT-23", "SOT-23-3L")).isTrue();
        assertThat(Recognizers.samePackage("SOT-23", "TO-236AB")).isTrue();
        assertThat(Recognizers.samePackage("SOT-23", "DFN-8L")).isFalse();
    }

    // ---------------------------------------------------------------- ranking, counts, hint, configuration

    @Test
    void excludedPartsAreCountedPerConstraint() {
        ParsedQuery q = parser.parse("22uF X7R 1206 MLCC");
        String category = "Capacitors / Multilayer Ceramic Capacitors MLCC - SMD/SMT";
        Map<Distributor, List<Part>> fetched = new EnumMap<>(Distributor.class);
        fetched.put(Distributor.LCSC, List.of(lcsc("OK", "25V 22uF X7R ±10% 1206", category, "1206"),
                lcsc("V1", "25V 10uF X7R ±10% 1206", category, "1206"),
                lcsc("V2", "25V 4.7uF X7R ±10% 1206", category, "1206"),
                lcsc("P1", "25V 22uF X7R ±10% 1210", category, "1210")));
        KinaProperties props = RankingFixtures.properties("kina.ranking.cross-encoder.enabled", "false");
        RankingService service = new RankingService(props, ranker, new RankingServiceTest.FakeRanker(),
                () -> RankingServiceTest.READY, new RankingScoreCache(props.ranking().scoreCacheTtl()));
        RankingService.RankedResults r = service.rank(q, fetched, Duration.ofSeconds(1));
        assertThat(r.byDistributor().get(Distributor.LCSC)).extracting(p -> p.part().distributorPartNumber())
                .containsExactly("OK");
        assertThat(r.excludedBy(Distributor.LCSC)).isEqualTo(3);
        assertThat(r.excludedDetailBy(Distributor.LCSC)).containsOnly(Map.entry("capacitance", 2),
                Map.entry("package", 1));
    }

    @Test
    void theHintNamesTheHardConstraintsAndPromisesNoSubstitutes() {
        ParsedQuery q = parser.parse("22uF X7R 1206 25V MLCC");
        String hint = policy.hint(q, List.of("TME"), Map.of("package", 9, "capacitance", 3), 2, false);
        assertThat(hint).isEqualTo("No in-stock 22uF capacitor in package 1206 at TME; package and capacitance are "
                + "never relaxed. 2 parts were left out for a rating below the request; pass \"allow_below_spec\": "
                + "true to see them. No substitutes are returned; try another package or value.");
        // nothing fetched at all: the stated hard constraints are named
        assertThat(policy.hint(q, List.of("LCSC", "MOUSER"), Map.of(), 0, false))
                .startsWith("No in-stock 22uF capacitor in package 1206 at LCSC and MOUSER; capacitance and "
                        + "package are never relaxed.");
        // with allow_below_spec the rating sentence is left out
        assertThat(policy.hint(q, List.of("TME"), Map.of(), 3, true)).doesNotContain("allow_below_spec");
        assertThat(policy.hint(parser.parse("SOT-23 N-channel MOSFET 30V"), List.of("TME"), Map.of("polarity", 4), 0,
                false)).startsWith("No in-stock N-channel mosfet in package SOT-23 at TME; polarity and package are never "
                + "relaxed.");
    }

    @Test
    void hardConstraintsCanBeConfiguredPerFamily() {
        KinaProperties props = RankingFixtures.properties("kina.search.hard-constraints.inductor",
                "type,value,package,mounting");
        ConstraintPolicy configured = ConstraintPolicy.from(props.search());
        ParsedQuery q = parser.parse("10uH 20% inductor 0805");
        assertThat(configured.isHard(q, "package")).isTrue();
        assertThat(configured.check(q, extractor.features(lcsc("A", "10uH ±20% 1A 1210",
                "Inductors/Coils/Transformers / Inductors (SMD)", "1210"))).conflicts()).containsExactly("package");
        // the ladder no longer drops the package of an inductor
        assertThat(DistributorPhraser.ladder(Distributor.TME, q, q.originalText(), configured))
                .extracting(DistributorPhraser.Relaxation::phrase).containsExactly("inductor 10uH 0805");
        // other families keep the defaults; unknown names are ignored
        assertThat(configured.hardFor(parser.parse("22uF 1206 MLCC"))).isEqualTo(
                java.util.Set.copyOf(ConstraintPolicy.DEFAULT_HARD.get("capacitor")));
        ConstraintPolicy odd = ConstraintPolicy.from(RankingFixtures.properties(
                "kina.search.hard-constraints.capacitor", "value,colour").search());
        assertThat(odd.hardFor(parser.parse("22uF 1206 MLCC"))).containsExactly("value");
        // the deprecated strict-constraints still removes mounting, technology or elements when missing
        ConstraintPolicy legacy = ConstraintPolicy.from(RankingFixtures.properties(
                "kina.search.strict-constraints", "technology,elements").search());
        assertThat(legacy.hardFor(parser.parse("22uF 1206 MLCC"))).doesNotContain("mounting").contains("package");
        // unset or empty: the decided defaults
        assertThat(ConstraintPolicy.from(RankingFixtures.properties("kina.search.strict-constraints", "").search())
                .table()).isEqualTo(policy.table());
    }

    @Test
    void onlyRelaxableConstraintsAreReportedAsRelaxed() {
        ParsedQuery capacitor = parser.parse("22uF X7R 1206 25V MLCC");
        // LCSC's database search may drop the package or the rating term; neither is ever relaxed for a capacitor
        assertThat(ResponseAssembler.relaxable(capacitor, List.of("dielectric", "package", "voltage"), policy))
                .containsExactly("dielectric");
        assertThat(ResponseAssembler.relaxable(parser.parse("10uH inductor 0805"), List.of("package", "inductance"),
                policy)).containsExactly("package");
    }
}
