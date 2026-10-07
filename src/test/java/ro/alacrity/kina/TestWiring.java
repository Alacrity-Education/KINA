package ro.alacrity.kina;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.experimental.UtilityClass;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.metrics.KinaMetrics;
import ro.alacrity.kina.metrics.MetricsStore;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Binds {@link KinaProperties} from {@code kina.*} key and value pairs on top of the defaults. */
    public KinaProperties properties(String... keysAndValues) {
        Map<String, String> source = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            source.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        source.putIfAbsent("kina.public-base-url", "");
        return new Binder(new MapConfigurationPropertySource(source))
                .bindOrCreate("kina", Bindable.of(KinaProperties.class));
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
