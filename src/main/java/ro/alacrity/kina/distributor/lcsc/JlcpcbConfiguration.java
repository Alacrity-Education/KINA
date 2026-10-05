package ro.alacrity.kina.distributor.lcsc;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} for the periodic JLCPCB database check ({@link JlcpcbDatabaseManager#scheduledCheck()}). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class JlcpcbConfiguration {
}
