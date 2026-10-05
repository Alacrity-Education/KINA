package ro.alacrity.kina.security;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import ro.alacrity.kina.TestcontainersConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Shared configuration of the development-mode MockMvc integration tests (one cached Spring context and Postgres
 * container for all of them).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest(properties = "kina.security.mode=dev")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestEchoController.Endpoint.class})
public @interface DevModeIntegrationTest {
}
