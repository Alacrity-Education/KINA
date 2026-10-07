package ro.alacrity.kina.metrics;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * KINA's Prometheus metrics (DESIGN.md 3.7): the counter store, the instrumentation facade, the persistence of the
 * counters, the database gauges and the REST request counter. The Prometheus endpoint itself is Spring Boot's
 * ({@code /actuator/prometheus} on the management port, {@code management.server.port}).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Slf4j
public class MetricsConfiguration implements WebMvcConfigurer {

    @Autowired private ObjectProvider<KinaMetrics> metrics;

    /**
     * The management chain of {@code SecurityConfig} permits every actuator request; that is only safe while actuator
     * has its own port.
     */
    @EventListener(ApplicationReadyEvent.class)
    void warnWhenActuatorSharesTheMainPort(ApplicationReadyEvent event) {
        Environment environment = event.getApplicationContext().getEnvironment();
        String management = environment.getProperty("management.server.port");
        String server = environment.getProperty("server.port", "8080");
        // two random ports (0, tests) are different ports
        if (management == null || management.isBlank() || management.equals(server) && !"0".equals(server)) {
            log.warn("management.server.port (KINA_METRICS_PORT) is not separate from server.port: the actuator and "
                    + "Prometheus endpoints are served WITHOUT authentication on the main port");
        }
    }

    /** Counts {@code /api/**} requests by matched path pattern ({@code kina_api_requests_total{endpoint}}). */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                        Exception ex) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                metrics.ifAvailable(m -> m.apiRequest(pattern == null ? "unmatched" : pattern.toString()));
            }
        }).addPathPatterns("/api/**");
    }
}
