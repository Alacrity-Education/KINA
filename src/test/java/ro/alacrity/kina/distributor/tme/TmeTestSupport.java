package ro.alacrity.kina.distributor.tme;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import ro.alacrity.kina.config.KinaProperties;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Fixtures and request matchers shared by the TME tests. */
final class TmeTestSupport {

    static final String BASE = "https://api.tme.test";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private TmeTestSupport() {
    }

    static KinaProperties.Tme properties(int maxResultsPerSearch) {
        return new KinaProperties.Tme("test-token", "test-secret", "RO", "EUR", "en", BASE, maxResultsPerSearch, 3);
    }

    /** Reads {@code src/test/resources/fixtures/tme/<name>}. */
    static String fixture(String name) {
        try (InputStream in = TmeTestSupport.class.getResourceAsStream("/fixtures/tme/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T fixture(String name, Class<T> type) {
        return TmeHttp.MAPPER.readValue(fixture(name), type);
    }

    /** Token response with the given access token value. */
    static String tokenJson(String accessToken) {
        return "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\",\"expires_in\":300,\"refresh_token\":\"r\"}";
    }

    static ResponseActions expectToken(MockRestServiceServer server, String accessToken) {
        ResponseActions actions = server.expect(requestTo(BASE + "/auth/token")).andExpect(method(HttpMethod.POST));
        actions.andRespond(withSuccess(tokenJson(accessToken), MediaType.APPLICATION_JSON));
        return actions;
    }

    /** Matches a GET to {@code path} (ignoring the query string). */
    static RequestMatcher get(String path) {
        return request -> {
            assertThat(request.getMethod()).isEqualTo(HttpMethod.GET);
            assertThat(request.getURI().getRawPath()).isEqualTo(path);
            assertThat(BASE).startsWith(request.getURI().getScheme() + "://" + request.getURI().getHost());
        };
    }

    /** Matches the URL-decoded values of a (possibly repeated) query parameter, in order. */
    static RequestMatcher query(String name, String... values) {
        return request -> assertThat(queryValues(request.getURI().getRawQuery(), name))
                .as("query parameter %s", name).containsExactly(values);
    }

    static RequestMatcher queryCount(String name, int count) {
        return request -> assertThat(queryValues(request.getURI().getRawQuery(), name))
                .as("query parameter %s", name).hasSize(count);
    }

    static List<String> queryValues(String rawQuery, String name) {
        List<String> values = new ArrayList<>();
        if (rawQuery == null) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equals(name)) {
                values.add(eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    /** A clock that tests can move forward. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
