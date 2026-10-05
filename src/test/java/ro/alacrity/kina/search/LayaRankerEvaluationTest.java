package ro.alacrity.kina.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.client.RestClient;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.ParsedQuery;
import ro.alacrity.kina.domain.Part;
import ro.alacrity.kina.domain.RankingMode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static ro.alacrity.kina.search.RankingFixtures.attrs;

/**
 * Labelled evaluation against a live Laya server (DESIGN.md section 3.5). Runs only when {@code KINA_LAYA_TEST_URL}
 * is set, e.g. {@code KINA_LAYA_TEST_URL=http://127.0.0.1:8001 ./mvnw test -Dtest=LayaRankerEvaluationTest}.
 * Re-run it after swapping in a fine-tuned checkpoint ({@code KINA_LAYA_TEST_MODEL}).
 */
@EnabledIfEnvironmentVariable(named = "KINA_LAYA_TEST_URL", matches = ".+")
class LayaRankerEvaluationTest {

    static final String QUERY = "10uF X7R 0805 MLCC ceramic capacitor";

    private static Part mouser(String mpn, String manufacturer, String description, String category, String pkg,
                               Map<String, String> attributes) {
        return RankingFixtures.part(Distributor.MOUSER, "EVAL-" + mpn, manufacturer, mpn, description, category, pkg,
                5000, "0.10", attributes, Map.of());
    }

    /** 3 true matches followed by 7 distractors. */
    static final List<Part> MATCHES = List.of(
            mouser("CL21B106KOQNNNE", "Samsung Electro-Mechanics",
                    "10uF ±10% 16V X7R 0805 Multilayer Ceramic Capacitors MLCC - SMD/SMT ROHS",
                    "Capacitors/Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805",
                    attrs("Capacitance", "10uF", "Voltage Rated", "16V", "Tolerance", "±10%",
                            "Temperature Coefficient", "X7R")),
            mouser("GRM21BR71A106KA73L", "Murata Electronics",
                    "Multilayer Ceramic Capacitors MLCC - SMD/SMT 0805 10uF 10volts X7R 10%",
                    "Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805 (2012 metric)",
                    attrs("Capacitance", "10 uF", "Voltage Rating DC", "10 VDC", "Dielectric", "X7R",
                            "Tolerance", "10 %", "Case Code - in", "0805")),
            mouser("CC0805KKX7R7BB106", "YAGEO", "Capacitor: ceramic; MLCC; 10uF; 16V; X7R; ±10%; SMD; 0805",
                    "MLCC SMD capacitors", "0805",
                    attrs("Capacitance", "10µF", "Operating voltage", "16V", "Dielectric", "X7R", "Tolerance", "±10%",
                            "Case - inch", "0805", "Mounting", "SMD")));

    static final List<Part> DISTRACTORS = List.of(
            mouser("RC0805FR-0710KL", "YAGEO", "Thick Film Resistors - SMD 1/8W 10K ohm 1% 0805",
                    "Thick Film Resistors - SMD", "0805",
                    attrs("Resistance", "10 kOhms", "Power Rating", "125 mW (1/8 W)", "Tolerance", "1 %")),
            mouser("CL21A106KAYNNNE", "Samsung Electro-Mechanics",
                    "10uF ±10% 25V X5R 0805 Multilayer Ceramic Capacitors MLCC - SMD/SMT ROHS",
                    "Capacitors/Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805",
                    attrs("Capacitance", "10uF", "Voltage Rated", "25V", "Tolerance", "±10%",
                            "Temperature Coefficient", "X5R")),
            mouser("CL31B106KBHNNNE", "Samsung Electro-Mechanics",
                    "10uF ±10% 50V X7R 1206 Multilayer Ceramic Capacitors MLCC - SMD/SMT ROHS",
                    "Capacitors/Multilayer Ceramic Capacitors MLCC - SMD/SMT", "1206",
                    attrs("Capacitance", "10uF", "Voltage Rated", "50V", "Tolerance", "±10%",
                            "Temperature Coefficient", "X7R")),
            mouser("CL21B105KBFNNNE", "Samsung Electro-Mechanics",
                    "1uF ±10% 50V X7R 0805 Multilayer Ceramic Capacitors MLCC - SMD/SMT ROHS",
                    "Capacitors/Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805",
                    attrs("Capacitance", "1uF", "Voltage Rated", "50V", "Tolerance", "±10%",
                            "Temperature Coefficient", "X7R")),
            mouser("CL21F106ZOCNNNC", "Samsung Electro-Mechanics",
                    "10uF -20%~+80% 16V Y5V 0805 Multilayer Ceramic Capacitors MLCC - SMD/SMT ROHS",
                    "Capacitors/Multilayer Ceramic Capacitors MLCC - SMD/SMT", "0805",
                    attrs("Capacitance", "10uF", "Voltage Rated", "16V", "Temperature Coefficient", "Y5V")),
            mouser("TAJA106K016RNJ", "KYOCERA AVX", "Tantalum Capacitors - Solid SMD 16V 10uF 1206 10% ESR=3Ohms",
                    "Tantalum Capacitors - Solid SMD", "1206 (3216 metric)",
                    attrs("Capacitance", "10 uF", "Voltage Rating DC", "16 VDC", "Tolerance", "10 %")),
            mouser("LQM21FN100M70L", "Murata Electronics", "Fixed Inductors 10uH 0805 20% 0.02A",
                    "Fixed Inductors", "0805", attrs("Inductance", "10 uH", "Tolerance", "20 %")));

    @Test
    void blendedRankingPutsAllTrueMatchesInTopThree() throws RankingException {
        String url = System.getenv("KINA_LAYA_TEST_URL");
        String model = System.getenv().getOrDefault("KINA_LAYA_TEST_MODEL", "multilingual");
        KinaProperties properties = RankingFixtures.properties("kina.ranking.laya.url", url,
                "kina.ranking.laya.model", model, "kina.ranking.timeout", "60s");
        ParametricExtractor extractor = new ParametricExtractor();
        DeterministicRanker deterministic = new DeterministicRanker(extractor);
        LayaPartRanker laya = new LayaPartRanker(properties, extractor,
                new StaticListableBeanFactory().getBeanProvider(RestClient.Builder.class));
        RankingScoreCache cache = new RankingScoreCache(Duration.ofHours(1));
        RankingService service = new RankingService(properties, deterministic, laya, laya::isHealthy, cache);
        ParsedQuery query = new QueryParser().parse(QUERY);
        List<Part> all = new ArrayList<>(MATCHES);
        all.addAll(DISTRACTORS);
        Set<String> matchKeys = Set.copyOf(MATCHES.stream().map(Part::key).toList());

        long start = System.nanoTime();
        Map<String, Double> raw = laya.rank(query, all, Duration.ofSeconds(60));
        long millis = (System.nanoTime() - start) / 1_000_000;
        raw.forEach((k, v) -> cache.put(query.normalizedKey(), k, v));
        Map<String, Double> normalised = RankingService.normalise(raw);

        RankingService.RankedResults result = service.rank(query, Map.of(Distributor.MOUSER, all),
                Duration.ofSeconds(60));

        System.out.printf(Locale.ROOT, "Laya evaluation: model=%s, %d states, 1 question, %d ms%n", model, all.size(),
                millis);
        System.out.printf(Locale.ROOT, "%-4s %-20s %-6s %8s %8s %8s %8s%n", "rank", "mpn", "label", "laya", "norm",
                "det", "final");
        int rank = 1;
        for (RankingService.RankedPart rp : result.byDistributor().get(Distributor.MOUSER)) {
            String key = rp.part().key();
            System.out.printf(Locale.ROOT, "%-4d %-20s %-6s %8.4f %8.3f %8.3f %8.3f%n", rank++,
                    rp.part().manufacturerPartNumber(), matchKeys.contains(key) ? "MATCH" : "-", raw.get(key),
                    normalised.get(key), deterministic.score(query, rp.part()), rp.score());
        }
        double matchMean = MATCHES.stream().mapToDouble(p -> raw.get(p.key())).average().orElseThrow();
        double distractorMean = DISTRACTORS.stream().mapToDouble(p -> raw.get(p.key())).average().orElseThrow();
        long layaTop3 = raw.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(3).filter(e -> matchKeys.contains(e.getKey())).count();
        System.out.printf(Locale.ROOT, "raw mean: matches %.4f, distractors %.4f; Laya-only top-3 matches: %d/3%n",
                matchMean, distractorMean, layaTop3);

        assertThat(raw).hasSize(10).allSatisfy((k, v) -> assertThat(v).isBetween(0.0, 1.0));
        assertThat(result.mode()).isEqualTo(RankingMode.LAYA);
        List<String> top3 = result.byDistributor().get(Distributor.MOUSER).stream().limit(3)
                .map(rp -> rp.part().key()).toList();
        assertThat(top3).containsExactlyInAnyOrderElementsOf(matchKeys);
        assertThat(laya.isHealthy()).isTrue();
    }
}
