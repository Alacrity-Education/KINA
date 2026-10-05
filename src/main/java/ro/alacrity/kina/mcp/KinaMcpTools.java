package ro.alacrity.kina.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP tools exposed on {@code /mcp} (DESIGN.md section 4). Methods annotated with {@link McpTool} are picked up
 * by the Spring AI annotation scanner (stateless server, sync).
 */
@Component
public class KinaMcpTools {

    private final String version;

    public KinaMcpTools(@Value("${spring.ai.mcp.server.version:dev}") String version) {
        this.version = version;
    }

    @McpTool(name = "ping", description = "Health check. Returns {\"status\":\"ok\",\"version\":...} when the KINA MCP server is reachable.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public Ping ping() {
        return new Ping("ok", version);
    }

    public record Ping(@JsonProperty("status") String status, @JsonProperty("version") String version) {
    }
}
