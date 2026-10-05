package ro.alacrity.kina.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import ro.alacrity.kina.config.KinaProperties;
import ro.alacrity.kina.web.PublicUrlResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Two filter chains (DESIGN.md sections 6 and 7).
 * <ol>
 *   <li><b>Machine chain</b> ({@code /api/**}, {@code /mcp/**}, OAuth endpoints, {@code /.well-known/**}): stateless,
 *   CSRF off, CORS for the endpoints browser-based MCP clients call, bearer tokens only. {@code /api/**} and
 *   {@code /mcp/**} need a valid KINA access token (production) or fall back to the development admin (development).
 *   OAuth endpoints are public and authenticate clients themselves.</li>
 *   <li><b>Web chain</b> (everything else: token UI, {@code /oauth/authorize}): session based, CSRF on. Production:
 *   OIDC login through the single {@code oidc} registration; development: every request runs as the admin.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** Endpoints protected by bearer tokens. */
    static final String[] PROTECTED_MACHINE_PATHS = {"/api/**", "/mcp", "/mcp/**"};

    /** Public OAuth / discovery endpoints (clients authenticate themselves where needed). */
    static final String[] PUBLIC_OAUTH_PATHS = {"/.well-known/**", "/oauth/register", "/oauth/token", "/oauth/revoke"};

    /** CORS-enabled paths (browser-based MCP clients). */
    static final String[] CORS_PATHS = {"/.well-known/**", "/oauth/register", "/oauth/token", "/oauth/revoke", "/mcp",
            "/mcp/**"};

    static final String[] PUBLIC_WEB_PATHS = {"/actuator/health", "/actuator/health/**", "/actuator/info", "/error",
            "/login-error", "/login-denied", "/favicon.ico", "/css/**", "/js/**", "/images/**", "/webjars/**"};

    @Bean
    @Order(1)
    SecurityFilterChain machineSecurityFilterChain(HttpSecurity http, KinaProperties properties,
                                                   AccessTokenService tokens, UserRepository users,
                                                   MembershipVerifier membership, PublicUrlResolver urls,
                                                   ObjectProvider<DevAdminProvider> devAdmin) {
        RequestMatcher protectedPaths = matcher(PROTECTED_MACHINE_PATHS);
        BearerAuthenticationEntryPoint entryPoint = new BearerAuthenticationEntryPoint(urls);
        BearerTokenAuthenticationFilter bearerFilter =
                new BearerTokenAuthenticationFilter(protectedPaths, tokens, users, membership, entryPoint);

        http.securityMatcher(Stream.concat(Arrays.stream(PROTECTED_MACHINE_PATHS), Arrays.stream(PUBLIC_OAUTH_PATHS))
                        .toArray(String[]::new))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_OAUTH_PATHS).permitAll()
                        .anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint))
                .addFilterBefore(bearerFilter, AnonymousAuthenticationFilter.class);
        if (properties.security().mode() == KinaProperties.Mode.DEV) {
            http.addFilterAfter(new DevModeAuthenticationFilter(protectedPaths, devAdmin.getObject()),
                    BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http, KinaProperties properties,
                                               ObjectProvider<DevAdminProvider> devAdmin,
                                               ObjectProvider<OidcUserSynchronizer> oidcUserSynchronizer,
                                               UserRepository users, MembershipVerifier membership) {
        http.csrf(Customizer.withDefaults())
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
        if (properties.security().mode() == KinaProperties.Mode.DEV) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .addFilterBefore(new DevModeAuthenticationFilter(AnyRequestMatcher.INSTANCE, devAdmin.getObject()),
                            AnonymousAuthenticationFilter.class);
        } else {
            OidcUserSynchronizer synchronizer = oidcUserSynchronizer.getObject();
            http.authorizeHttpRequests(auth -> auth
                            .requestMatchers(PUBLIC_WEB_PATHS).permitAll()
                            .anyRequest().authenticated())
                    // A single provider: unauthenticated users go straight to it (no provider chooser page);
                    // the saved request (e.g. /oauth/authorize?...) is resumed after login.
                    .oauth2Login(login -> login
                            .loginPage("/oauth2/authorization/" + LazyOidcClientRegistrationRepository.REGISTRATION_ID)
                            .failureHandler(SecurityConfig::loginFailure)
                            .authorizedClientRepository(new UpstreamTokenCapturingClientRepository(membership))
                            .tokenEndpoint(token -> token.accessTokenResponseClient(
                                    OidcHttp.authorizationCodeTokenClient()))
                            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(synchronizer)))
                    .addFilterAfter(new RevokedUserSessionFilter(users), AnonymousAuthenticationFilter.class)
                    .logout(logout -> logout.logoutSuccessUrl("/login-error?logout"));
        }
        return http.build();
    }

    /**
     * Failed OIDC login: a group or e-mail-domain refusal goes to {@code /login-denied} (which names the required
     * group), anything else to {@code /login-error}. No session is established in either case.
     */
    static void loginFailure(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException exception) throws IOException {
        String target = "/login-error";
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            String code = oauth2.getError().getErrorCode();
            if (OidcAccessPolicy.ERROR_GROUP.equals(code)) {
                target = "/login-denied?reason=group";
            } else if (OidcAccessPolicy.ERROR_EMAIL_DOMAIN.equals(code)) {
                target = "/login-denied?reason=email";
            }
        }
        if ("/login-error".equals(target)) {
            LoggerFactory.getLogger(SecurityConfig.class).warn("OIDC login failed: {}", exception.getMessage());
        }
        response.sendRedirect(request.getContextPath() + target);
    }

    static UrlBasedCorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(List.of("*"));
        cors.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setExposedHeaders(List.of(HttpHeaders.WWW_AUTHENTICATE, "Mcp-Session-Id", "Mcp-Protocol-Version"));
        cors.setAllowCredentials(false);
        cors.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        for (String path : CORS_PATHS) {
            source.registerCorsConfiguration(path, cors);
        }
        return source;
    }

    private static RequestMatcher matcher(String... patterns) {
        PathPatternRequestMatcher.Builder builder = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(Arrays.stream(patterns).<RequestMatcher>map(builder::matcher).toList());
    }
}
