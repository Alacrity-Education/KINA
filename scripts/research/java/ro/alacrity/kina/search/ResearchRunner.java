package ro.alacrity.kina.search;

import ro.alacrity.kina.distributor.lcsc.JlcpcbRow;
import ro.alacrity.kina.distributor.lcsc.JlcpcbSqliteSearch;
import ro.alacrity.kina.distributor.lcsc.LcscPartMapper;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Research harness bridge to the production Java ranking code (not part of the application build).
 * Lives in package {@code ro.alacrity.kina.search} so it can reach the package-private feature extraction.
 *
 * <pre>
 * lcsc  &lt;db&gt; &lt;limit&gt; &lt;query&gt;...      native LCSC retrieval (JlcpcbSqliteSearch + LcscPartMapper), one JSON line per query
 * score &lt;dataset.jsonl&gt; &lt;out.jsonl&gt;   DeterministicRanker score + per-signal feature vector per candidate
 * </pre>
 */
public final class ResearchRunner {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "lcsc" -> lcsc(Path.of(args[1]), Integer.parseInt(args[2]), List.of(args).subList(3, args.length));
            case "score" -> score(Path.of(args[1]), Path.of(args[2]));
            default -> throw new IllegalArgumentException("unknown mode " + args[0]);
        }
    }

    private static void lcsc(Path db, int limit, List<String> queries) throws Exception {
        JlcpcbSqliteSearch search = new JlcpcbSqliteSearch(db);
        Instant now = Instant.parse("2026-10-05T00:00:00Z");
        for (String q : queries) {
            JlcpcbSqliteSearch.Result result = search.search(q, 0, limit);
            List<Part> parts = new ArrayList<>();
            for (JlcpcbRow row : result.rows()) {
                LcscPartMapper.map(row, now).ifPresent(parts::add);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("query", q);
            out.put("total", result.total());
            out.put("mode", result.mode().name());
            out.put("parts", parts);
            System.out.println(MAPPER.writeValueAsString(out));
        }
        search.close();
    }

    @SuppressWarnings("unchecked")
    private static void score(Path in, Path out) throws Exception {
        ParametricExtractor extractor = new ParametricExtractor();
        DeterministicRanker ranker = new DeterministicRanker(extractor);
        QueryParser parser = new QueryParser();
        try (BufferedReader r = Files.newBufferedReader(in, StandardCharsets.UTF_8);
             BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> rec = MAPPER.readValue(line, Map.class);
                ParsedQuery q = parser.parse((String) rec.get("query"));
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Map<String, Object> c : (List<Map<String, Object>>) rec.get("candidates")) {
                    Map<String, Object> partMap = new LinkedHashMap<>((Map<String, Object>) c.get("part"));
                    Part part = extractor.enrich(MAPPER.convertValue(partMap, Part.class));
                    long t0 = System.nanoTime();
                    double det = ranker.score(q, part);
                    long nanos = System.nanoTime() - t0;
                    ParametricExtractor.Features f = extractor.features(part);
                    Map<String, Object> feat = features(q, part, f);
                    double sum = 0;
                    for (String k : List.of("value", "package", "dielectric", "rating", "tolerance", "family", "lexical")) {
                        sum += ((Number) feat.get("w_" + k)).doubleValue();
                    }
                    sum += DeterministicRanker.tieBreak(part);
                    double check = Math.clamp(sum, 0.0, 1.0);
                    boolean decompositionOk = Math.abs(check - det) <= 1e-9;
                    if (!decompositionOk && System.getenv("RESEARCH_LENIENT") == null) {
                        throw new IllegalStateException("feature decomposition mismatch for " + part.key()
                                + ": " + check + " vs " + det);
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", c.get("key"));
                    row.put("det", det);
                    row.put("nanos", nanos);
                    row.put("decomposition_ok", decompositionOk);
                    row.put("features", feat);
                    row.put("comparable", extractor.extract(part));
                    rows.add(row);
                }
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", rec.get("id"));
                Map<String, Object> parsed = new LinkedHashMap<>();
                parsed.put("family", q.family());
                Map<String, Object> cons = new LinkedHashMap<>();
                q.constraints().forEach((k, v) -> cons.put(k, v.display()));
                parsed.put("constraints", cons);
                parsed.put("dielectric", q.dielectric());
                parsed.put("package", q.packageName());
                parsed.put("mounting", q.mounting());
                parsed.put("keywords", q.keywords());
                o.put("parsed", parsed);
                o.put("candidates", rows);
                w.write(MAPPER.writeValueAsString(o));
                w.newLine();
            }
        }
    }

    /**
     * Mirrors {@link DeterministicRanker#score}: signed indicator per signal (+1 match, -1 mismatch, 0 unknown or not
     * requested) and the weighted contribution {@code w_*}, so the sum reproduces the production score exactly.
     */
    static Map<String, Object> features(ParsedQuery query, Part part, ParametricExtractor.Features f) {
        Map<String, Object> m = new LinkedHashMap<>();
        double value = 0;
        String primary = DeterministicRanker.primaryKind(query);
        if (primary != null) {
            Double pv = f.value(primary);
            if (pv != null) {
                value = DeterministicRanker.sameValue(query.constraint(primary).value(), pv,
                        DeterministicRanker.VALUE_MATCH_TOLERANCE) ? 1 : -1;
            }
        }
        double pkg = 0;
        String wp = Recognizers.packageKey(query.packageName());
        String pp = Recognizers.packageKey(f.packageName());
        if (wp != null && pp != null) {
            pkg = wp.equals(pp) ? 1 : -1;
        }
        double diel = 0;
        if (query.dielectric() != null && f.dielectric() != null) {
            diel = query.dielectric().equalsIgnoreCase(f.dielectric()) ? 1 : -1;
        }
        double rating = 0;
        List<String> requested = List.of(ParsedQuery.VOLTAGE, ParsedQuery.CURRENT, ParsedQuery.POWER).stream()
                .filter(k -> query.constraint(k) != null).toList();
        for (String kind : requested) {
            Double pv = f.value(kind);
            if (pv == null) {
                continue;
            }
            double wanted = query.constraint(kind).value();
            boolean exact = kind.equals(ParsedQuery.VOLTAGE)
                    && ("regulator".equals(query.family()) || "zener".equals(query.family()));
            boolean ok = exact ? DeterministicRanker.sameValue(wanted, pv, DeterministicRanker.EXACT_VOLTAGE_TOLERANCE)
                    : pv >= wanted * (1 - 1e-9);
            rating += (ok ? 1.0 : -1.0) / requested.size();
        }
        double tol = 0;
        ParsedQuery.Constraint t = query.constraint(ParsedQuery.TOLERANCE);
        Double pt = f.value(ParsedQuery.TOLERANCE);
        if (t != null && pt != null) {
            tol = pt <= t.value() + 1e-9 ? 1 : -1;
        }
        double fam = familySign(query.family(), f);
        double lex = 0;
        if (!query.keywords().isEmpty()) {
            long found = query.keywords().stream().filter(k -> f.text().contains(k)).count();
            lex = (double) found / query.keywords().size();
        }
        double stock = part.stock() > 0
                ? Math.min(1.0, Math.log10(part.stock()) / DeterministicRanker.STOCK_SATURATION_LOG10) : 0;
        double price = part.prices().isEmpty() ? 0 : 1;
        double library = 0;
        for (Map.Entry<String, Object> e : part.extra().entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().contains("library") && e.getValue() != null) {
                String v = e.getValue().toString().toLowerCase();
                if (v.contains("basic") || v.contains("preferred")) {
                    library = 1;
                    break;
                }
            }
        }
        m.put("value", value);
        m.put("package", pkg);
        m.put("dielectric", diel);
        m.put("rating", rating);
        m.put("tolerance", tol);
        m.put("family", fam);
        m.put("lexical", lex);
        m.put("stock", stock);
        m.put("price", price);
        m.put("library", library);
        m.put("w_value", DeterministicRanker.W_PRIMARY_VALUE * value);
        m.put("w_package", DeterministicRanker.W_PACKAGE * pkg);
        m.put("w_dielectric", DeterministicRanker.W_DIELECTRIC * diel);
        m.put("w_rating", DeterministicRanker.W_RATING * rating);
        m.put("w_tolerance", DeterministicRanker.W_TOLERANCE * tol);
        m.put("w_family", DeterministicRanker.W_FAMILY * fam);
        m.put("w_lexical", DeterministicRanker.W_LEXICAL * lex);
        return m;
    }

    /** Copy of the private {@code DeterministicRanker.familyScore}, as a sign (+1 / 0 / -1). */
    private static double familySign(String wanted, ParametricExtractor.Features f) {
        if (wanted == null) {
            return 0;
        }
        String actual = f.family();
        if (actual != null) {
            if (actual.equals(wanted) || wanted.equals(Recognizers.parentFamily(actual))) {
                return 1;
            }
            if (actual.equals(Recognizers.parentFamily(wanted))) {
                return 0;
            }
            return -1;
        }
        for (String word : Recognizers.familyWords(wanted)) {
            if (f.text().contains(word)) {
                return 1;
            }
        }
        return 0;
    }
}
