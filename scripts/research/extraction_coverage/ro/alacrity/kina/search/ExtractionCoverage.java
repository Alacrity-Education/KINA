package ro.alacrity.kina.search;

import ro.alacrity.kina.distributor.lcsc.JlcpcbRow;
import ro.alacrity.kina.distributor.lcsc.LcscPartMapper;
import ro.alacrity.kina.domain.ComponentFamily;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.PartAttribute;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Research runner (not part of the build): measures what the real attribute extractor ({@link ParametricExtractor})
 * would put into a field index. Lives in package ro.alacrity.kina.search to read the package-private features.
 * Usage: ExtractionCoverage MODE SAMPLE_DB CACHE_TSV OUT_DIR   (MODE = coverage | throughput | determinism)
 */
public class ExtractionCoverage {

    static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    static final ParametricExtractor EXTRACTOR = new ParametricExtractor();

    /** The attributes reported in the coverage table: label, then the keys any of which counts. */
    static final String[][] COLS = {
            {"Cap", "Capacitance"}, {"Res", "Resistance"}, {"Ind", "Inductance"}, {"Volt", "Voltage"},
            {"Curr", "Current", "RatedCurrent"}, {"Pwr", "Power"}, {"Tol", "Tolerance"}, {"Pkg", "Package"},
            {"Mount", "Mounting"}, {"Diel", "Dielectric"}, {"Tech", "Technology"}, {"ConnType", "ConnectorType"},
            {"Pos", "Positions"}, {"Pitch", "Pitch"}, {"Gender", "Gender"}, {"Orient", "Orientation"}};

    record Item(Part part, Map<String, String> attrs, String family, Map<String, Double> si) { }

    // ------------------------------------------------------------------ loading
    static List<JlcpcbRow> loadRows(String db) throws Exception {
        List<JlcpcbRow> rows = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT \"LCSC Part\",\"First Category\",\"Second Category\",\"MFR.Part\",\"Package\",\"Solder Joint\","
                             + "\"Manufacturer\",\"Library Type\",\"Description\",\"Datasheet\",\"Price\",\"Stock\" "
                             + "FROM sample " + (System.getProperty("strat") == null ? "" : "WHERE strat = '" + System.getProperty("strat") + "' ") + "ORDER BY rowid_, strat")) {
            while (rs.next()) {
                rows.add(new JlcpcbRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getString(10), rs.getString(11), rs.getString(12)));
            }
        }
        return rows;
    }

    static List<Part> loadCache(String tsv) throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        List<Part> parts = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(tsv))) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            try {
                parts.add(json.readValue(line.substring(tab + 1), Part.class));
            } catch (RuntimeException e) {
                System.err.println("unreadable payload: " + e.getMessage());
            }
        }
        return parts;
    }

    static List<Part> mapAll(List<JlcpcbRow> rows) {
        List<Part> out = new ArrayList<>(rows.size());
        for (JlcpcbRow r : rows) {
            LcscPartMapper.map(r, NOW).ifPresent(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ extraction
    static Item analyse(Part stored) {
        Map<String, String> attrs = EXTRACTOR.extract(stored);
        ParametricExtractor.Features f = EXTRACTOR.features(stored);
        Map<String, Double> si = new HashMap<>();
        f.values().forEach((k, v) -> si.put(k, v.value()));
        return new Item(stored, attrs, attrs.get(ParametricExtractor.FAMILY), si);
    }

    static String dist(Part p) {
        return p.distributor().name();
    }

    // ------------------------------------------------------------------ main
    public static void main(String[] a) throws Exception {
        String mode = a[0];
        List<JlcpcbRow> rows = loadRows(a[1]);
        List<Part> lcsc = mapAll(rows);
        List<Part> cache = loadCache(a[2]);
        Path out = Path.of(a[3]);
        Files.createDirectories(out);
        System.err.printf("lcsc rows %d parts %d cache parts %d%n", rows.size(), lcsc.size(), cache.size());
        switch (mode) {
            case "coverage" -> coverage(lcsc, cache, out);
            case "throughput" -> throughput(rows, out);
            case "determinism" -> determinism(lcsc, cache, out);
            default -> throw new IllegalArgumentException(mode);
        }
    }

    // ------------------------------------------------------------------ coverage, cardinality, normalisation
    static String pct(long n, long d) {
        return d == 0 ? "-" : String.format(Locale.ROOT, "%.1f", 100.0 * n / d);
    }

    static boolean has(Item it, String[] col) {
        for (int i = 1; i < col.length; i++) {
            if (it.attrs().containsKey(col[i])) {
                return true;
            }
        }
        return false;
    }

    static boolean hasPrimaryValue(Item i) {
        for (String k : new String[] {"Voltage", "Resistance", "Capacitance", "Inductance", "Current", "RatedCurrent"}) {
            if (i.attrs().containsKey(k)) {
                return true;
            }
        }
        return false;
    }

    record Prim(String fam, String kind, Pattern raw) { }

    static void coverage(List<Part> lcsc, List<Part> cache, Path out) throws Exception {
        List<Part> all = new ArrayList<>(lcsc);
        all.addAll(cache.stream().map(Part::asStored).toList());
        List<Item> items = all.parallelStream().map(ExtractionCoverage::analyse).collect(Collectors.toList());
        StringBuilder md = new StringBuilder();

        md.append("### Population and unknown family\n\n| Distributor | Parts | Family unknown | Family unknown % | Blank description % |\n|---|---:|---:|---:|---:|\n");
        for (Distributor d : Distributor.values()) {
            long n = items.stream().filter(i -> i.part().distributor() == d).count();
            if (n == 0) {
                continue;
            }
            long unk = items.stream().filter(i -> i.part().distributor() == d && i.family() == null).count();
            long blankD = items.stream().filter(i -> i.part().distributor() == d && (i.part().description() == null || i.part().description().isBlank())).count();
            md.append(String.format("| %s | %d | %d | %s | %s |%n", d, n, unk, pct(unk, n), pct(blankD, n)));
        }
        md.append('\n');

        for (Distributor d : Distributor.values()) {
            Map<String, Long> cats = items.stream().filter(i -> i.part().distributor() == d && i.family() == null)
                    .collect(Collectors.groupingBy(i -> String.valueOf(i.part().category()), Collectors.counting()));
            if (cats.isEmpty()) {
                continue;
            }
            Map<String, Long> total = items.stream().filter(i -> i.part().distributor() == d)
                    .collect(Collectors.groupingBy(i -> String.valueOf(i.part().category()), Collectors.counting()));
            md.append("### Unknown family, top categories: ").append(d).append("\n\n| Category | Unknown | In sample | Unknown % |\n|---|---:|---:|---:|\n");
            cats.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey())).limit(15)
                    .forEach(e -> md.append(String.format("| %s | %d | %d | %s |%n", esc(e.getKey()),
                            e.getValue(), total.get(e.getKey()), pct(e.getValue(), total.get(e.getKey())))));
            md.append('\n');
        }

        md.append("### Family by category (LCSC sample): the families each category resolves to\n\n| Category | Parts | Families (count) |\n|---|---:|---|\n");
        Map<String, List<Item>> byCat = items.stream().filter(i -> i.part().distributor() == Distributor.LCSC)
                .collect(Collectors.groupingBy(i -> String.valueOf(i.part().category()), TreeMap::new, Collectors.toList()));
        byCat.entrySet().stream().sorted((x, y) -> y.getValue().size() - x.getValue().size()).limit(60).forEach(e -> {
            Map<String, Long> fc = e.getValue().stream().collect(Collectors.groupingBy(i -> String.valueOf(i.family()), Collectors.counting()));
            md.append(String.format("| %s | %d | %s |%n", esc(e.getKey()), e.getValue().size(), top(fc, 4)));
        });
        md.append('\n');

        Map<String, Long> famTotal = items.stream().filter(i -> i.family() != null)
                .collect(Collectors.groupingBy(Item::family, Collectors.counting()));
        List<String> fams = famTotal.entrySet().stream().filter(e -> e.getValue() >= 200)
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList();
        md.append("### Coverage: percent of parts with a typed value, per family and distributor\n\n");
        md.append("| Family | Dist | Parts |");
        for (String[] c : COLS) {
            md.append(' ').append(c[0]).append(" |");
        }
        md.append("\n|---|---|---:|").append("---:|".repeat(COLS.length)).append('\n');
        for (String fam : fams) {
            for (Distributor d : Distributor.values()) {
                List<Item> sel = items.stream().filter(i -> fam.equals(i.family()) && i.part().distributor() == d).toList();
                if (sel.size() < 20) {
                    continue;
                }
                md.append(String.format("| %s | %s | %d |", fam, d, sel.size()));
                for (String[] c : COLS) {
                    md.append(' ').append(pct(sel.stream().filter(i -> has(i, c)).count(), sel.size())).append(" |");
                }
                md.append('\n');
            }
        }
        md.append('\n');

        md.append("### Coverage per category (percent of the category's parts; LCSC categories with at least 3000 sampled parts, others at least 150)\n\n| Distributor | Category | Parts |");
        for (String[] c : COLS) {
            md.append(' ').append(c[0]).append(" |");
        }
        md.append("\n|---|---|---:|").append("---:|".repeat(COLS.length)).append('\n');
        for (Distributor d : Distributor.values()) {
            Map<String, List<Item>> cats = items.stream().filter(i -> i.part().distributor() == d)
                    .collect(Collectors.groupingBy(i -> String.valueOf(i.part().category()), TreeMap::new, Collectors.toList()));
            cats.entrySet().stream().filter(e -> e.getValue().size() >= (d == Distributor.LCSC ? 3000 : 150))
                    .sorted((x, y) -> y.getValue().size() - x.getValue().size()).forEach(e -> {
                        md.append(String.format("| %s | %s | %d |", d, esc(e.getKey()), e.getValue().size()));
                        for (String[] c : COLS) {
                            md.append(' ').append(pct(e.getValue().stream().filter(i -> has(i, c)).count(), e.getValue().size())).append(" |");
                        }
                        md.append('\n');
                    });
        }
        md.append('\n');

        md.append("### Coverage per policy family (all distributors)\n\n| Policy family | Parts |");
        for (String[] c : COLS) {
            md.append(' ').append(c[0]).append(" |");
        }
        md.append("\n|---|---:|").append("---:|".repeat(COLS.length)).append('\n');
        Map<String, List<Item>> byPolicy = items.stream().filter(i -> ComponentFamily.of(i.family()) != null)
                .collect(Collectors.groupingBy(i -> ComponentFamily.of(i.family()).policy().name(), TreeMap::new, Collectors.toList()));
        byPolicy.forEach((p, sel) -> {
            if (sel.size() < 200) {
                return;
            }
            md.append(String.format("| %s | %d |", p, sel.size()));
            for (String[] c : COLS) {
                md.append(' ').append(pct(sel.stream().filter(i -> has(i, c)).count(), sel.size())).append(" |");
            }
            md.append('\n');
        });
        md.append('\n');

        md.append("### Typed fields per part\n\n| Distributor | Mean attrs/part | Parts with >=3 attrs % | With Package % | With any of Voltage/Resistance/Capacitance/Inductance/Current % |\n|---|---:|---:|---:|---:|\n");
        for (Distributor d : Distributor.values()) {
            List<Item> sel = items.stream().filter(i -> i.part().distributor() == d).toList();
            if (sel.isEmpty()) {
                continue;
            }
            double mean = sel.stream().mapToInt(i -> i.attrs().size()).average().orElse(0);
            long ge3 = sel.stream().filter(i -> i.attrs().size() >= 3).count();
            long pk = sel.stream().filter(i -> i.attrs().containsKey("Package")).count();
            long val = sel.stream().filter(ExtractionCoverage::hasPrimaryValue).count();
            md.append(String.format(Locale.ROOT, "| %s | %.2f | %s | %s | %s |%n", d, mean, pct(ge3, sel.size()), pct(pk, sel.size()), pct(val, sel.size())));
        }
        md.append('\n');

        md.append("### Cardinality per attribute (all distributors)\n\n| Attribute | Parts with value | Distinct | Top 10 values (count) |\n|---|---:|---:|---|\n");
        Map<String, Map<String, Long>> perAttr = new TreeMap<>();
        for (Item it : items) {
            it.attrs().forEach((k, v) -> perAttr.computeIfAbsent(k, x -> new HashMap<>()).merge(v, 1L, Long::sum));
        }
        perAttr.entrySet().stream().sorted((x, y) -> Long.compare(sum(y.getValue()), sum(x.getValue()))).forEach(e ->
                md.append(String.format("| %s | %d | %d | %s |%n", e.getKey(), sum(e.getValue()), e.getValue().size(), top(e.getValue(), 10))));
        md.append('\n');

        md.append("### Cardinality of attributes per family (parts with a value, distinct values, top 10)\n\n| Family | Attribute | Parts | Distinct | Top 10 (count) |\n|---|---|---:|---:|---|\n");
        for (String fam : fams) {
            for (String key : List.of("Capacitance", "Resistance", "Inductance", "Voltage", "Current", "RatedCurrent", "Power", "Tolerance", "Package", "Dielectric", "Technology", "Positions", "Pitch", "ConnectorType", "Gender", "Orientation", "Mounting")) {
                Map<String, Long> m = items.stream().filter(i -> fam.equals(i.family()) && i.attrs().containsKey(key))
                        .collect(Collectors.groupingBy(i -> i.attrs().get(key), Collectors.counting()));
                if (sum(m) >= 50) {
                    md.append(String.format("| %s | %s | %d | %d | %s |%n", fam, key, sum(m), m.size(), top(m, 10)));
                }
            }
        }
        md.append('\n');

        // normalisation of the primary value
        md.append("### Normalisation of the primary value\n\n");
        List<Prim> prims = List.of(
                new Prim("capacitor", PartAttribute.CAPACITANCE.kind(), Pattern.compile("(?i)(?<![\\w.])(\\d+(?:[.,]\\d+)?)\\s*(pf|nf|uf|µf|μf|mf|f)(?![a-z0-9])")),
                new Prim("resistor", PartAttribute.RESISTANCE.kind(), Pattern.compile("(?i)(?<![\\w.])(\\d+(?:[.,]\\d+)?)\\s*(mohms?|kohms?|megohms?|ohms?|mΩ|kΩ|MΩ|Ω)(?![a-z0-9])|(?<![\\w.])(\\d+(?:\\.\\d+)?[kKmMrR]\\d*)(?![a-zA-Z0-9.])")),
                new Prim("inductor", PartAttribute.INDUCTANCE.kind(), Pattern.compile("(?i)(?<![\\w.])(\\d+(?:[.,]\\d+)?)\\s*(nh|uh|µh|μh|mh|h)(?![a-z0-9])")));
        md.append("| Family | Parts with value | Distinct SI doubles (all parts) | Distinct after rounding to 9 significant digits | Single-value descriptions: distinct SI doubles | Distinct raw spellings | Spellings per SI double (mean) | Max spellings for one double |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
        StringBuilder spell = new StringBuilder();
        for (Prim p : prims) {
            Map<Double, Set<String>> bySi = new TreeMap<>();
            Set<Double> allDoubles = new java.util.HashSet<>();
            Set<java.math.BigDecimal> rounded = new java.util.HashSet<>();
            long withValue = 0;
            for (Item it : items) {
                if (!p.fam().equals(it.family()) || !it.si().containsKey(p.kind())) {
                    continue;
                }
                withValue++;
                allDoubles.add(it.si().get(p.kind()));
                rounded.add(new java.math.BigDecimal(it.si().get(p.kind())).round(new java.math.MathContext(9)).stripTrailingZeros());
                Matcher m = p.raw().matcher(it.part().description() == null ? "" : it.part().description());
                if (m.find()) {
                    String first = m.group().trim();
                    if (m.find()) {
                        continue;   // several values in the description: the first token need not be the extracted one
                    }
                    bySi.computeIfAbsent(it.si().get(p.kind()), x -> new TreeSet<>()).add(first);
                }
            }
            int spellings = bySi.values().stream().mapToInt(Set::size).sum();
            int maxS = bySi.values().stream().mapToInt(Set::size).max().orElse(0);
            md.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %.2f | %d |%n", p.fam(), withValue, allDoubles.size(), rounded.size(), bySi.size(), spellings,
                    bySi.isEmpty() ? 0 : (double) spellings / bySi.size(), maxS));
            spell.append("\n").append(p.fam()).append(": SI values with the most raw spellings\n\n| SI value | Spellings |\n|---:|---|\n");
            bySi.entrySet().stream().sorted((x, y) -> y.getValue().size() - x.getValue().size()).limit(8)
                    .forEach(e -> spell.append("| ").append(e.getKey()).append(" | ").append(esc(String.join(", ", e.getValue()))).append(" |\n"));
        }
        md.append(spell).append('\n');

        // gaps
        md.append("### Gaps: primary value not extracted though the description shows one\n\n");
        Map<String, Long> gapCount = new TreeMap<>();
        Map<String, List<Item>> gapItems = new TreeMap<>();
        for (Item it : items) {
            String desc = it.part().description() == null ? "" : it.part().description();
            String cat = String.valueOf(it.part().category()).toLowerCase(Locale.ROOT);
            for (Prim p : prims) {
                boolean famMatch = p.fam().equals(it.family()) || (it.family() == null && cat.contains(p.fam()));
                if (!famMatch || it.si().containsKey(p.kind())) {
                    continue;
                }
                if (p.raw().matcher(desc).find()) {
                    String key = p.fam() + "/" + dist(it.part());
                    gapCount.merge(key, 1L, Long::sum);
                    gapItems.computeIfAbsent(key, x -> new ArrayList<>()).add(it);
                }
            }
        }
        md.append("| Family / distributor | Parts with a visible value but none extracted | Parts of the family (or category) | Gap % |\n|---|---:|---:|---:|\n");
        gapCount.forEach((k, v) -> {
            String fam = k.substring(0, k.indexOf('/'));
            String d = k.substring(k.indexOf('/') + 1);
            long denom = items.stream().filter(i -> dist(i.part()).equals(d) && (fam.equals(i.family())
                    || (i.family() == null && String.valueOf(i.part().category()).toLowerCase(Locale.ROOT).contains(fam)))).count();
            md.append(String.format("| %s | %d | %d | %s |%n", k, v, denom, pct(v, denom)));
        });
        md.append('\n');
        List<Item> examples = new ArrayList<>();
        List<String> keys = new ArrayList<>(gapItems.keySet());
        for (int idx = 0; examples.size() < 30 && idx < 1000; idx++) {
            for (String k : keys) {
                List<Item> l = gapItems.get(k);
                int stride = Math.max(1, l.size() / 10);
                int at = idx * stride + stride / 2;
                if (at < l.size() && examples.size() < 30) {
                    examples.add(l.get(at));
                }
            }
        }
        Map<String, Long> classes = new TreeMap<>();
        gapItems.forEach((k, l) -> l.forEach(it -> classes.merge(k + " : " + gapClass(it.part().description()), 1L, Long::sum)));
        md.append("| Family/distributor : class | Parts |\n|---|---:|\n");
        classes.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        md.append("\n| # | Dist | Family | Class | Part | Category | Package | Description |\n|---:|---|---|---|---|---|---|---|\n");
        int n = 1;
        for (Item it : examples) {
            Part p = it.part();
            String d = esc(p.description());
            md.append(String.format("| %d | %s | %s | %s | %s | %s | %s | %s |%n", n++, p.distributor(), it.family(),
                    gapClass(p.description()), p.distributorPartNumber(), esc(p.category()), esc(p.packageName()),
                    d.length() > 220 ? d.substring(0, 220) + "..." : d));
        }
        md.append('\n');

        // primary value missing for any reason
        md.append("### Primary value missing (any reason): family parts without Capacitance / Resistance / Inductance\n\n");
        md.append("| Family | Dist | Parts | Missing | Missing % | Of which no value-like token in the description | Of which blank description |\n|---|---|---:|---:|---:|---:|---:|\n");
        List<String> missExamples = new ArrayList<>();
        for (Prim p : prims) {
            String key = p.fam().substring(0, 1).toUpperCase(Locale.ROOT) + p.fam().substring(1);
            String attr = p.fam().equals("capacitor") ? "Capacitance" : p.fam().equals("resistor") ? "Resistance" : "Inductance";
            for (Distributor d : Distributor.values()) {
                List<Item> sel = items.stream().filter(i -> p.fam().equals(i.family()) && i.part().distributor() == d).toList();
                if (sel.isEmpty()) {
                    continue;
                }
                List<Item> miss = sel.stream().filter(i -> !i.attrs().containsKey(attr)).toList();
                long noToken = miss.stream().filter(i -> !p.raw().matcher(String.valueOf(i.part().description())).find()).count();
                long blank = miss.stream().filter(i -> i.part().description() == null || i.part().description().isBlank()).count();
                md.append(String.format("| %s | %s | %d | %d | %s | %d | %d |%n", p.fam(), d, sel.size(), miss.size(), pct(miss.size(), sel.size()), noToken, blank));
                miss.stream().filter(i -> !p.raw().matcher(String.valueOf(i.part().description())).find())
                        .filter(i -> i.part().description() != null && i.part().description().hashCode() % 17 == 0).limit(4)
                        .forEach(i -> missExamples.add(String.format("| %s | %s | %s | %s | %s |", p.fam(), d, i.part().distributorPartNumber(),
                                esc(i.part().category()), esc(i.part().description()).replaceAll("^(.{0,200}).*$", "$1"))));
            }
        }
        md.append("\nExamples of family parts with no primary value and no value-like token (hash-picked):\n\n| Family | Dist | Part | Category | Description |\n|---|---|---|---|---|\n");
        missExamples.forEach(s -> md.append(s).append('\n'));
        md.append('\n');

        md.append("### Suspicious extracted values\n\n| Check | Parts | Examples (part: value <- description) |\n|---|---:|---|\n");
        suspicious(md, items, "capacitor Tolerance >= 50%", i -> "capacitor".equals(i.family()) && percentOf(i.attrs().get("Tolerance")) >= 50);
        suspicious(md, items, "transistor Current in uA", i -> "transistor".equals(i.family()) && String.valueOf(i.attrs().get("Current")).endsWith("uA"));
        suspicious(md, items, "comparator Current in uA", i -> "comparator".equals(i.family()) && String.valueOf(i.attrs().get("Current")).endsWith("uA"));
        suspicious(md, items, "resistor Inductance", i -> "resistor".equals(i.family()) && i.attrs().containsKey("Inductance"));
        suspicious(md, items, "capacitor Resistance (ESR read as resistance)", i -> "capacitor".equals(i.family()) && i.attrs().containsKey("Resistance"));
        md.append('\n');
        Files.writeString(out.resolve("coverage.md"), md.toString());
        System.err.println("wrote " + out.resolve("coverage.md"));
    }

    static String gapClass(String desc) {
        String d = desc == null ? "" : desc;
        if (d.contains("、")) {
            return "multi-value list";
        }
        if (Pattern.compile("(?i)\\d\\s*(pf|nf|uf|uh|nh|mh)\\s*(\\+/-|±|\\+-)").matcher(d).find()) {
            return "value glued to tolerance";
        }
        return "other";
    }

    static double percentOf(String s) {
        if (s == null || !s.endsWith("%")) {
            return -1;
        }
        try {
            return Double.parseDouble(s.substring(0, s.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static void suspicious(StringBuilder md, List<Item> items, String label, java.util.function.Predicate<Item> test) {
        List<Item> hit = items.stream().filter(test).toList();
        String ex = hit.stream().filter(i -> i.part().description() != null && i.part().description().hashCode() % 11 == 0).limit(3)
                .map(i -> i.part().distributorPartNumber() + ": " + i.attrs().entrySet().stream().filter(e -> label.contains(e.getKey()) || label.startsWith(e.getKey())).map(Map.Entry::getValue).findFirst().orElse("")
                        + " <- " + esc(i.part().description()).replaceAll("^(.{0,110}).*$", "$1"))
                .collect(Collectors.joining("; "));
        md.append(String.format("| %s | %d | %s |%n", label, hit.size(), ex));
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("|", "/").replace("\n", " ");
    }

    static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }

    static String top(Map<String, Long> m, int n) {
        return m.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(n).map(e -> esc(e.getKey()) + " (" + e.getValue() + ")").collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ throughput
    static long runOnce(List<JlcpcbRow> rows, int threads) throws Exception {
        long t0 = System.nanoTime();
        long sink;
        if (threads <= 1) {
            sink = 0;
            for (JlcpcbRow r : rows) {
                Optional<Part> p = LcscPartMapper.map(r, NOW);
                if (p.isPresent()) {
                    sink += EXTRACTOR.enrich(p.get()).attributes().size();
                }
            }
        } else {
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                sink = pool.submit(() -> rows.parallelStream().mapToLong(r -> LcscPartMapper.map(r, NOW)
                        .map(p -> (long) EXTRACTOR.enrich(p).attributes().size()).orElse(0L)).sum()).get();
            } finally {
                pool.shutdown();
            }
        }
        if (sink < 0) {
            System.err.println(sink);
        }
        return System.nanoTime() - t0;
    }

    static void throughput(List<JlcpcbRow> rowsIn, Path out) throws Exception {
        List<JlcpcbRow> rows = rowsIn;
        int repeat = Integer.getInteger("repeat", 1);
        if (repeat > 1) {
            List<JlcpcbRow> copies = new ArrayList<>();
            for (int i = 0; i < repeat; i++) {
                copies.addAll(rows);
            }
            rows = copies;
        }
        StringBuilder md = new StringBuilder();
        Runtime rt = Runtime.getRuntime();
        md.append(String.format("rows=%d maxHeapMiB=%d cores=%d%n", rows.size(), rt.maxMemory() >> 20, rt.availableProcessors()));
        List<JlcpcbRow> warm = rows.subList(0, Math.min(40_000, rows.size()));
        runOnce(warm, 1);
        runOnce(warm, 8);
        for (int threads : new int[] {1, 8, 16}) {
            for (int run = 0; run < 3; run++) {
                long ns = runOnce(rows, threads);
                md.append(String.format(Locale.ROOT, "threads=%d run=%d seconds=%.2f rows_per_s=%.0f%n", threads, run + 1, ns / 1e9, rows.size() / (ns / 1e9)));
            }
        }
        System.gc();
        md.append(String.format("usedHeapMiBAfterGc=%d%n", (rt.totalMemory() - rt.freeMemory()) >> 20));
        Files.writeString(out.resolve("throughput-" + (rt.maxMemory() >> 20) + "m" + (System.getProperty("strat") == null ? "" : "-" + System.getProperty("strat")) + ".txt"), md.toString());
        System.out.print(md);
    }

    // ------------------------------------------------------------------ determinism
    static String hashOf(List<Part> parts, boolean sortedKeys, int threads) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        List<String> lines;
        if (threads <= 1) {
            lines = parts.stream().map(p -> render(EXTRACTOR.enrich(p), sortedKeys)).collect(Collectors.toList());
        } else {
            ForkJoinPool pool = new ForkJoinPool(threads);
            try {
                lines = pool.submit(() -> parts.parallelStream().map(p -> render(EXTRACTOR.enrich(p), sortedKeys)).collect(Collectors.toList())).get();
            } finally {
                pool.shutdown();
            }
        }
        for (String l : lines) {
            md.update(l.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    static String render(Part enriched, boolean sortedKeys) {
        Map<String, String> m = sortedKeys ? new TreeMap<>(enriched.attributes()) : enriched.attributes();
        return enriched.key() + "|" + m + "|" + new TreeSet<>(enriched.derivedAttributes());
    }

    static void determinism(List<Part> lcsc, List<Part> cache, Path out) throws Exception {
        List<Part> all = new ArrayList<>(lcsc);
        all.addAll(cache.stream().map(Part::asStored).toList());
        StringBuilder md = new StringBuilder();
        md.append("hash_ordered_single=").append(hashOf(all, false, 1)).append('\n');
        md.append("hash_ordered_single_again=").append(hashOf(all, false, 1)).append('\n');
        md.append("hash_sorted_single=").append(hashOf(all, true, 1)).append('\n');
        md.append("hash_ordered_parallel16=").append(hashOf(all, false, 16)).append('\n');
        md.append("hash_sorted_parallel16=").append(hashOf(all, true, 16)).append('\n');
        long notIdem = all.stream().filter(p -> {
            Part e = EXTRACTOR.enrich(p);
            return !EXTRACTOR.enrich(e).attributes().equals(e.attributes());
        }).count();
        md.append("enrich_not_idempotent=").append(notIdem).append('\n');
        List<String> orderDiffs = new ArrayList<>();
        for (Part p : cache) {
            if (p.attributes().size() < 2) {
                continue;
            }
            List<Map.Entry<String, String>> es = new ArrayList<>(p.asStored().attributes().entrySet());
            Collections.reverse(es);
            Map<String, String> rev = new LinkedHashMap<>();
            es.forEach(e -> rev.put(e.getKey(), e.getValue()));
            Map<String, String> a = new TreeMap<>(EXTRACTOR.enrich(p.asStored()).attributes());
            Map<String, String> b = new TreeMap<>(EXTRACTOR.enrich(p.asStored().toBuilder().attributes(rev).build()).attributes());
            if (!a.equals(b)) {
                Map<String, String> onlyA = new TreeMap<>(a);
                b.forEach((k, v) -> onlyA.remove(k, v));
                Map<String, String> onlyB = new TreeMap<>(b);
                a.forEach((k, v) -> onlyB.remove(k, v));
                orderDiffs.add(p.key() + " cat=" + p.category() + " original-order=" + onlyA + " reversed-order=" + onlyB);
            }
        }
        long orderDiff = orderDiffs.size();
        md.append("cache_parts_whose_values_change_with_distributor_attribute_order=").append(orderDiff).append('\n');
        orderDiffs.forEach(x -> md.append("  ").append(x).append('\n'));
        Files.writeString(out.resolve("determinism-" + ProcessHandle.current().pid() + ".txt"), md.toString());
        System.out.print(md);
    }
}
