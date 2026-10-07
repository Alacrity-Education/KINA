package ro.alacrity.kina;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.experimental.UtilityClass;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.metrics.MetricsStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds beans by hand for unit tests, the way Spring would: the bean's no-arg constructor, its {@code @Autowired}
 * fields set by name ({@link ReflectionTestUtils#setField}, so a renamed field fails loudly), then its
 * {@code @PostConstruct} methods. Tests use these helpers instead of reflection of their own.
 */
@UtilityClass
public class TestWiring {

    /**
     * Sets the fields given as name and value pairs on {@code bean}, then runs its {@code @PostConstruct} methods
     * (superclass first). Fields left out keep their defaults.
     */
    public <T> T wire(T bean, Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("fields come as name and value pairs");
        }
        for (int i = 0; i < namesAndValues.length; i += 2) {
            ReflectionTestUtils.setField(bean, (String) namesAndValues[i], namesAndValues[i + 1]);
        }
        List<Method> init = new ArrayList<>();
        for (Class<?> type = bean.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostConstruct.class)) {
                    init.addFirst(method);
                }
            }
        }
        for (Method method : init) {
            ReflectionUtils.makeAccessible(method);
            ReflectionUtils.invokeMethod(method, bean);
        }
        return bean;
    }

    /** A {@link MetricsStore} registering on {@code registry}; null keeps the values in memory only. */
    public MetricsStore metricsStore(MeterRegistry registry) {
        return wire(new MetricsStore(), "registry", registry);
    }

    /** {@link KinaMetrics} on {@code store}, without attaching it to {@code RateLimitRetry} (no @PostConstruct). */
    public KinaMetrics metrics(MetricsStore store) {
        KinaMetrics metrics = new KinaMetrics();
        ReflectionTestUtils.setField(metrics, "store", store);
        return metrics;
    }
}
