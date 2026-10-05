package ro.alacrity.kina.security;

import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Explicit timeouts for every call KINA makes to the OIDC provider (login code exchange, userinfo, JWKS, membership
 * re-checks). Spring Security's defaults have none, so a hanging provider would hang the login request.
 * Discovery itself ({@code ClientRegistrations.fromIssuerLocation}) uses Spring's internal client.
 */
public final class OidcHttp {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private OidcHttp() {
    }

    static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    /**
     * {@link java.net.http.HttpClient} for the membership re-check (never follows redirects). HTTP/1.1 only: on plain
     * {@code http} the JDK client otherwise attempts an {@code h2c} upgrade, and some providers' servers (verified with
     * Authentik 2026.8) then drop the POST body of the token request ({@code unsupported_grant_type}).
     */
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** RestTemplate for JWKS downloads. */
    static RestTemplate jwksRestTemplate() {
        return new RestTemplate(requestFactory());
    }

    /** Authorization-code token client of the login, same converters as Spring's default plus timeouts. */
    static RestClientAuthorizationCodeTokenResponseClient authorizationCodeTokenClient() {
        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory())
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        return client;
    }

    /** OIDC user service whose userinfo call has timeouts. */
    static OidcUserService oidcUserService() {
        RestTemplate userInfo = new RestTemplate(requestFactory());
        userInfo.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultOAuth2UserService oauth2UserService = new DefaultOAuth2UserService();
        oauth2UserService.setRestOperations(userInfo);
        OidcUserService service = new OidcUserService();
        service.setOauth2UserService(oauth2UserService);
        return service;
    }
}
