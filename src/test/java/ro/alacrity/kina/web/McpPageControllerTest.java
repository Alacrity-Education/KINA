package ro.alacrity.kina.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import ro.alacrity.kina.security.AccessTokenRepository.AccessToken;
import ro.alacrity.kina.security.AccessTokenService;
import ro.alacrity.kina.security.DevModeIntegrationTest;
import ro.alacrity.kina.security.UserRepository;

import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@DevModeIntegrationTest
class McpPageControllerTest {

    static final Pattern TOKEN = Pattern.compile("kina_[A-Za-z0-9_-]{43}");

    @Autowired
    MockMvc mvc;

    @Autowired
    AccessTokenService tokens;

    @Autowired
    UserRepository users;

    @Test
    void indexRendersForDevAdmin() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/connect")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString())
                .contains("Access tokens")
                .contains("Signed in as Development Admin")
                .contains("name=\"_csrf\"")
                .contains("http://localhost/mcp")
                // TME API terms 8.7: the notice wherever TME data is shown (DESIGN.md 3.2 "Attributions": the web footer only)
                .contains("Data powered by TME.eu Data – no guarantee of data accuracy")
                .contains("Product data provided by Mouser Electronics")
                .contains("<a href=\"/connect\" aria-current=\"page\" class=\"active\">MCP</a>")
                .contains("<a href=\"/\">Search</a>")
                .contains("<a href=\"/status\">Status</a>");
    }

    @Test
    void rootIsTheSearchTab() throws Exception {
        for (String path : new String[]{"/", "/search"}) {
            MockHttpServletResponse response = mvc.perform(get(path)).andReturn().getResponse();
            assertThat(response.getStatus()).as(path).isEqualTo(200);
            assertThat(response.getContentAsString()).as(path)
                    .contains("<a href=\"/\" aria-current=\"page\" class=\"active\">Search</a>")
                    .contains("<a href=\"/connect\">MCP</a>")
                    .contains("name=\"q\"")
                    .contains("name=\"distributors\"")
                    .contains("Connect KINA")
                    .doesNotContain("Create token");
        }
    }

    @Test
    void createShowsPlaintextOnceAndListsToken() throws Exception {
        String name = "laptop " + System.nanoTime();
        MockHttpServletResponse created = mvc.perform(post("/tokens").with(csrf()).formField("name", name))
                .andReturn().getResponse();
        assertThat(created.getStatus()).isEqualTo(200);
        assertThat(created.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        String page = created.getContentAsString();
        Matcher matcher = TOKEN.matcher(page);
        assertThat(matcher.find()).isTrue();
        String plaintext = matcher.group();
        assertThat(page).contains("claude mcp add --transport http kina http://localhost/mcp --header")
                .contains("Authorization: Bearer " + plaintext)
                .contains("shown only once");
        assertThat(tokens.validate(plaintext)).isPresent();

        String index = mvc.perform(get("/connect")).andReturn().getResponse().getContentAsString();
        assertThat(index).contains(name).contains(plaintext.substring(0, 12)).doesNotContain(plaintext);
    }

    @Test
    void createValidatesName() throws Exception {
        MockHttpServletResponse response = mvc.perform(post("/tokens").with(csrf()).formField("name", "   "))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("Please enter a name");

        MockHttpServletResponse tooLong = mvc.perform(post("/tokens").with(csrf()).formField("name", "x".repeat(101)))
                .andReturn().getResponse();
        assertThat(tooLong.getStatus()).isEqualTo(400);
    }

    @Test
    void createRequiresCsrf() throws Exception {
        assertThat(mvc.perform(post("/tokens").formField("name", "no csrf")).andReturn().getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    void revokeWorks() throws Exception {
        var issued = tokens.create(users.devAdmin().id(), "to revoke", null, null);
        MockHttpServletResponse response = mvc.perform(post("/tokens/" + issued.token().id() + "/revoke").with(csrf()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getRedirectedUrl()).isEqualTo("/connect");
        assertThat(tokens.validate(issued.plaintext())).isEmpty();
        assertThat(tokens.list(users.devAdmin().id())).filteredOn(t -> t.id().equals(issued.token().id()))
                .singleElement().extracting((AccessToken t) -> t.status(Instant.now())).isEqualTo("revoked");
    }

    @Test
    void cannotRevokeOtherUsersToken() throws Exception {
        var other = users.upsert("https://idp.example.com", "someone-" + System.nanoTime(), null, "Someone", null);
        var issued = tokens.create(other.id(), "theirs", null, null);
        mvc.perform(post("/tokens/" + issued.token().id() + "/revoke").with(csrf())).andReturn();
        assertThat(tokens.validate(issued.plaintext())).isPresent();
    }
}
