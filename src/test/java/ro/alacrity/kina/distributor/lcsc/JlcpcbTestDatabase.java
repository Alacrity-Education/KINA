package ro.alacrity.kina.distributor.lcsc;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/** Builds tiny JLCPCB-shaped SQLite databases (same FTS5 trigram schema as kicad-jlcpcb-tools) for tests. */
final class JlcpcbTestDatabase {

    static final List<JlcpcbRow> SAMPLE = List.of(
            row("C15850", "Capacitors", "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "CL21A106KAYNNNE", "0805",
                    "Samsung Electro-Mechanics", "Basic", "10uF 25V X5R ±10%", "1-199:0.0123,200-:0.0100", "5000000"),
            row("C15851", "Capacitors", "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "CL21B106KOQNNNE", "0805",
                    "Samsung Electro-Mechanics", "Basic", "10uF 16V X7R ±10%", "1-199:0.020,200-599:0.016,600-:0.014", "800000"),
            row("C99999", "Capacitors", "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "GRM21BR71C106KE11L", "0805",
                    "Murata", "Extended", "10uF 16V X7R ±10%", "1-:0.050", "0"),
            row("C1525", "Capacitors", "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "CL05B104KO5NNNC", "0402",
                    "Samsung Electro-Mechanics", "Basic", "100nF 16V X7R ±10%", "1-199:0.0011,200-:0.0009", "24700991"),
            row("C17414", "Resistors", "Chip Resistor - Surface Mount", "0805W8F1002T5E", "0805",
                    "UNI-ROYAL", "Basic", "-55℃~+155℃ 10kΩ 125mW 150V Thick Film Resistor ±1% ±100ppm/℃",
                    "1-199:0.0021,200-:0.0011", "51357854"),
            row("C17733", "Resistors", "Chip Resistor - Surface Mount", "0805W8F5103T5E", "0805",
                    "UNI-ROYAL", "Basic", "-55℃~+155℃ 125mW 150V 510kΩ Thick Film Resistor ±1% ±100ppm/℃",
                    "1-199:0.0021,200-:0.0011", "328051"),
            row("C25804", "Resistors", "Chip Resistor - Surface Mount", "0603WAF1002T5E", "0603",
                    "UNI-ROYAL", "Basic", "10kΩ 100mW 75V Thick Film Resistor ±1%", "1-:0.0010", "9000000"),
            row("C25000", "Resistors", "Chip Resistor - Surface Mount", "RC0805JR-071KL", "0805",
                    "YAGEO", "Extended", "1kΩ 125mW 150V Thick Film Resistor ±5%", "1-:0.0030", "12000"),
            row("C2040", "Embedded Processors & Controllers", "Microcontrollers (MCU/MPU/SOC)", "RP2040", "LQFP-56",
                    "Raspberry Pi", "Extended", "133MHz 264KB ARM Cortex-M0+ 2x\"dual core\"", "1-9:0.70,10-:0.65", "40000"));

    /** Connector rows with the real JLCPCB vocabulary (descriptions and packages copied from the 2026-10 database). */
    static final List<JlcpcbRow> CONNECTORS = List.of(
            row("C2897388", "Connectors", "Female Headers", "PM254-1-06-W-8.5", "Push-Pull,P=2.54mm", "HCTL", "Extended",
                    "-40℃~+105℃ 1 1x6P 2.54mm 3A 6P 8.5mm Copper alloy Right Angle Side Square Hole", "1-:0.05", "26473"),
            row("C5333441", "Connectors", "Female Headers", "A2541HWR-6P", "Push-Pull,P=2.54mm", "CJT", "Extended",
                    "-40℃~+105℃ 1 1x6P 2.54mm 2.5mm 250V 3.2mm 3A 6P Brass Gold Right Angle Side Square Hole 弯插,P=2.54mm",
                    "1-:0.04", "1935"),
            row("C50878477", "Connectors", "Female Headers", "HX FH254-01-06-W-H8.5", "Push-Pull,P=2.54mm", "hanxia",
                    "Extended", "-40℃~+105℃ 1 1kV 1x6P 2.54mm 3.2mm 3A 6P 8.5mm Gold Phosphor bronze Right Angle Side"
                            + " Square Hole 弯插,P=2.54mm", "1-:0.04", "1962"),
            row("C2897423", "Connectors", "Female Headers", "PM254-2-03-W-8.5", "Push-Pull,P=2.54mm", "HCTL", "Extended",
                    "-40℃~+105℃ 2 2.54mm 2.54mm 2x3P 3A 6P 8.5mm Copper alloy Right Angle Side Square Hole", "1-:0.06",
                    "15355"),
            row("C2897404", "Connectors", "Female Headers", "PM254-2-03-Z-8.5", "Plugin,P=2.54mm", "HCTL", "Extended",
                    "-40℃~+105℃ 2 2.54mm 2.54mm 2x3P 3A 6P 8.5mm Copper alloy Square Hole Through Hole Top", "1-:0.05",
                    "46331"),
            row("C5142239", "Connectors", "Female Headers", "X6511FRS-06-C85D30", "SMD,P=2.54mm,Surface Mount，Right Angle",
                    "XKB", "Extended", "-40℃~+105℃ 1 1x6P 2.54mm 3A 6P 8.5mm Side Square Hole Surface Mount, Right Angle"
                            + " SMD,P=2.54mm,卧贴", "1-:0.10", "2357"),
            row("C2906055", "Connectors", "Female Headers", "PM200-1-06-W-4.3", "Push-Pull,P=2mm", "HCTL", "Extended",
                    "-40℃~+105℃ 1 1.5A 1x6P 2mm 4.3mm 6P Brass Right Angle Side Square Hole", "1-:0.05", "942"),
            row("C2897395", "Connectors", "Female Headers", "PM254-1-16-W-8.5", "Push-Pull,P=2.54mm", "HCTL", "Extended",
                    "-40℃~+105℃ 1 1x16P 2.54mm 3A 16P 8.5mm Copper alloy Right Angle Side Square Hole", "1-:0.09", "5000"),
            row("C2897300", "Connectors", "Female Headers", "PM254-1-06-W-OOS", "Push-Pull,P=2.54mm", "HCTL", "Extended",
                    "-40℃~+105℃ 1 1x6P 2.54mm 3A 6P 8.5mm Copper alloy Right Angle Side Square Hole", "1-:0.05", "0"),
            row("C2337", "Connectors", "Pin Headers", "2.54-1*40PStraight pin", "Plugin,P=2.54mm", "BOOMELE", "Basic",
                    "-25℃~+85℃ 1 1x40P 2.54mm 2.5mm 3A 3mm 40P 6mm Black Brass Gold Pin Header Through Hole 插件,P=2.54mm",
                    "1-:0.08", "89289"),
            row("C2334", "Connectors", "Pin Headers", "2.54-1*40PStraight Bent Pin", "Push-Pull,P=2.54mm", "BOOMELE",
                    "Extended", "-55℃~+105℃ 1 1x40P 2.54mm 2.54mm 3A 3mm 40P Black Brass Gold Pin Header Right Angle"
                            + " 弯插,P=2.54mm", "1-:0.09", "4652"));

    /** {@link #SAMPLE} plus {@link #CONNECTORS}. */
    static List<JlcpcbRow> withConnectors() {
        List<JlcpcbRow> rows = new java.util.ArrayList<>(SAMPLE);
        rows.addAll(CONNECTORS);
        return rows;
    }

    private JlcpcbTestDatabase() {
    }

    static JlcpcbRow row(String lcsc, String first, String second, String mpn, String pkg, String manufacturer,
            String library, String description, String price, String stock) {
        return new JlcpcbRow(lcsc, first, second, mpn, pkg, "2", manufacturer, library, description,
                "https://www.lcsc.com/datasheet/" + lcsc + ".pdf", price, stock);
    }

    static Path create(Path file) throws SQLException {
        return create(file, SAMPLE);
    }

    static Path create(Path file, List<JlcpcbRow> rows) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
                Statement st = c.createStatement()) {
            st.execute("""
                    CREATE VIRTUAL TABLE parts using fts5 (
                            'LCSC Part', 'First Category', 'Second Category', 'MFR.Part', 'Package',
                            'Solder Joint' unindexed, 'Manufacturer', 'Library Type', 'Description',
                            'Datasheet' unindexed, 'Price' unindexed, 'Stock' unindexed, tokenize="trigram")""");
            st.execute("CREATE TABLE meta ('filename', 'size', 'partcount', 'date', 'last_update')");
            st.execute("CREATE TABLE categories ('First Category', 'Second Category')");
            st.execute("CREATE TABLE mapping ('footprint', 'value', 'LCSC')");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO parts VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                for (JlcpcbRow r : rows) {
                    ps.setString(1, r.lcscPart());
                    ps.setString(2, r.firstCategory());
                    ps.setString(3, r.secondCategory());
                    ps.setString(4, r.mfrPart());
                    ps.setString(5, r.packageName());
                    ps.setInt(6, Integer.parseInt(r.solderJoint()));
                    ps.setString(7, r.manufacturer());
                    ps.setString(8, r.libraryType());
                    ps.setString(9, r.description());
                    ps.setString(10, r.datasheet());
                    ps.setString(11, r.price());
                    ps.setString(12, r.stock());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            st.execute("INSERT INTO meta VALUES ('cache.sqlite3', 4096, " + rows.size()
                    + ", '2026-09-26', '2026-09-26T10:26:40.047973')");
        }
        return file;
    }
}
