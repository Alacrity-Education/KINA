package ro.alacrity.kina.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/** Enables scheduling for {@link CacheMaintenance} and provides the default {@link Clock}. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class CacheConfiguration {

    /** UTC system clock unless another {@link Clock} bean is defined (tests may supply a fixed one). */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock kinaClock() {
        return Clock.systemUTC();
    }
}
