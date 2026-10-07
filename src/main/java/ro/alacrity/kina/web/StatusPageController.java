package ro.alacrity.kina.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.domain.Distributor;
import ro.alacrity.kina.domain.DistributorStatusResponse;
import ro.alacrity.kina.domain.MetricsSummary;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.metrics.MetricsBackfill;
import ro.alacrity.kina.metrics.MetricsGauges;
import ro.alacrity.kina.metrics.MetricsGauges.CacheCounts;
import ro.alacrity.kina.search.DistributorStatusService;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Status tab of the web UI (DESIGN.md section 6, "Web UI"): {@code GET /status}. Server-rendered from the beans that
 * already hold the values: {@link DistributorStatusService} (distributors, cache totals, ranking),
 * {@link MetricsGauges} (cache rows per distributor and type, users, tokens; as of their last refresh),
 * {@link KinaMetrics#summary()} (counters) and {@link MetricsBackfill#status()}. Reloading the page refreshes it.
 */
@Controller
public class StatusPageController {

    @Autowired private KinaProperties properties;
    @Autowired private PublicUrlResolver urls;
    @Autowired private DistributorStatusService distributorStatus;
    @Autowired private KinaMetrics metrics;
    @Autowired private MetricsGauges gauges;
    @Autowired private MetricsBackfill backfill;
    @Value("${spring.ai.mcp.server.version:dev}") private String version;

    /** The system card. */
    public record SystemInfo(String version, String mode, String baseUrl, String uptime, long memoryUsedMb,
                             long memoryMaxMb) {
    }

    /** One row of the cache table. */
    public record CacheRow(String distributor, CacheCounts counts) {
    }

    /** One row of the cache-by-type table: parts and searches per cached distributor, in {@code columns} order. */
    public record TypeRow(String type, List<Long> values) {
    }

    /** One small counter table: a title and its values by tag. */
    public record CounterGroup(String title, Map<String, Long> values) {
    }

    /** Users and tokens. */
    public record Users(long known, long revoked, long activeTokens) {
    }

    @GetMapping("/status")
    public ModelAndView status(Authentication authentication) {
        ModelAndView view = WebTabs.view("status", WebTabs.STATUS, WebTabs.user(authentication));
        view.addObject("system", system());
        DistributorStatusResponse status = distributorStatus.status();
        view.addObject("distributors", status.distributors());
        view.addObject("cache", status.cache());
        view.addObject("ranking", status.ranking());
        view.addObject("ttl", duration(properties.cache().ttl()));
        Map<Distributor, CacheCounts> counts = gauges.cacheCounts();
        view.addObject("cacheRows", counts.entrySet().stream()
                .map(e -> new CacheRow(e.getKey().name(), e.getValue())).toList());
        view.addObject("typeColumns", counts.keySet().stream().map(Distributor::name).toList());
        view.addObject("typeRows", typeRows(counts));
        MetricsSummary summary = metrics.summary();
        MetricsBackfill.Status backfillStatus = backfill.status();
        view.addObject("summary", summary);
        view.addObject("backfill", backfillStatus);
        view.addObject("counterGroups", List.of(
                new CounterGroup("Tool calls", summary.toolCalls()),
                new CounterGroup("Search queries by type", summary.searchQueriesByType()),
                new CounterGroup("Parts added to the cache", summary.cacheAdded()),
                new CounterGroup("Rate-limited calls", summary.rateLimitedCalls()),
                new CounterGroup("Backfill runs", backfillStatus.runs()),
                new CounterGroup("Moved by the backfill", backfillStatus.moved())));
        view.addObject("users", new Users(gauges.usersKnown(), gauges.usersRevoked(), gauges.tokensActive()));
        view.addObject("now", Instant.now());
        return view;
    }

    /** Every type with cached parts or searches, sorted; per distributor its parts then its searches. */
    static List<TypeRow> typeRows(Map<Distributor, CacheCounts> counts) {
        TreeSet<String> types = new TreeSet<>();
        counts.values().forEach(c -> {
            types.addAll(c.partsByType().keySet());
            types.addAll(c.searchesByType().keySet());
        });
        List<TypeRow> rows = new ArrayList<>();
        for (String type : types) {
            List<Long> values = new ArrayList<>();
            counts.values().forEach(c -> {
                values.add(c.partsByType().getOrDefault(type, 0L));
                values.add(c.searchesByType().getOrDefault(type, 0L));
            });
            rows.add(new TypeRow(type, values));
        }
        return rows;
    }

    private SystemInfo system() {
        Runtime runtime = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        return new SystemInfo(version, properties.security().mode().name().toLowerCase(Locale.ROOT), urls.baseUrl(),
                duration(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())),
                (runtime.totalMemory() - runtime.freeMemory()) / mb, runtime.maxMemory() / mb);
    }

    /** {@code 2d 3h 4m}, {@code 3h 4m}, {@code 4m 5s}. */
    static String duration(Duration d) {
        if (d.toDays() > 0) {
            return d.toDays() + "d " + d.toHoursPart() + "h " + d.toMinutesPart() + "m";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "h " + d.toMinutesPart() + "m";
        }
        return d.toMinutes() + "m " + d.toSecondsPart() + "s";
    }
}
