package ro.alacrity.kina.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * TODO replaced by security module: placeholder that permits every request so the skeleton runs.
 * CSRF stays on for web pages and is disabled only for the machine endpoints {@code /mcp/**} and {@code /api/**}.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    // TODO replaced by security module
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/mcp", "/mcp/**", "/api/**"));
        return http.build();
    }
}
