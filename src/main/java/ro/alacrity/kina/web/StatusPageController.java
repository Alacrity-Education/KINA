package ro.alacrity.kina.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.ModelAndView;
import ro.alacrity.kina.config.KinaProperties;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;

/** The Status tab of the web UI (DESIGN.md section 6, "Web UI"): {@code GET /status}. */
@Controller
public class StatusPageController {

    @Autowired private KinaProperties properties;
    @Autowired private PublicUrlResolver urls;
    @Value("${spring.ai.mcp.server.version:dev}") private String version;

    /** The system card. */
    public record SystemInfo(String version, String mode, String baseUrl, String uptime, long memoryUsedMb,
                             long memoryMaxMb) {
    }

    @GetMapping("/status")
    public ModelAndView status(Authentication authentication) {
        ModelAndView view = WebTabs.view("status", WebTabs.STATUS, WebTabs.user(authentication));
        view.addObject("system", system());
        return view;
    }

    private SystemInfo system() {
        Runtime runtime = Runtime.getRuntime();
        long mb = 1024L * 1024L;
        return new SystemInfo(version, properties.security().mode().name().toLowerCase(Locale.ROOT), urls.baseUrl(),
                uptime(Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime())),
                (runtime.totalMemory() - runtime.freeMemory()) / mb, runtime.maxMemory() / mb);
    }

    /** {@code 2d 3h 4m}, {@code 3h 4m}, {@code 4m 5s}. */
    static String uptime(Duration d) {
        if (d.toDays() > 0) {
            return d.toDays() + "d " + d.toHoursPart() + "h " + d.toMinutesPart() + "m";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "h " + d.toMinutesPart() + "m";
        }
        return d.toMinutes() + "m " + d.toSecondsPart() + "s";
    }
}
