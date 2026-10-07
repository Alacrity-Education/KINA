package ro.alacrity.kina.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ro.alacrity.kina.security.DevModeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** {@code kina.tokens.ui-enabled=false} turns the token forms off; the Search and Status tabs stay. */
@DevModeIntegrationTest
@TestPropertySource(properties = "kina.tokens.ui-enabled=false")
class TokensDisabledTabsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void searchAndStatusStayAndTheMcpTabExplainsTheConnector() throws Exception {
        MockHttpServletResponse search = mvc.perform(get("/")).andReturn().getResponse();
        assertThat(search.getStatus()).isEqualTo(200);
        assertThat(search.getContentAsString()).contains("name=\"q\"", ">Search</a>", ">MCP</a>", ">Status</a>")
                .contains("Static tokens are disabled");

        assertThat(mvc.perform(get("/status")).andReturn().getResponse().getStatus()).isEqualTo(200);

        MockHttpServletResponse mcp = mvc.perform(get("/connect")).andReturn().getResponse();
        assertThat(mcp.getStatus()).isEqualTo(200);
        assertThat(mcp.getContentAsString()).contains("Connect KINA through Claude")
                .contains("aria-current=\"page\" class=\"active\">MCP</a>")
                .doesNotContain("Create token");
    }
}
