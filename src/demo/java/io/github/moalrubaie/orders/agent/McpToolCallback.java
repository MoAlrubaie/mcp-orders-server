package io.github.moalrubaie.orders.agent;

import io.github.moalrubaie.orders.demo.Session;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hands one MCP tool to a Spring AI chat model: the model gets the tool's name, description and
 * input schema exactly as the server wrote them, and nothing else.
 */
final class McpToolCallback implements ToolCallback {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Session session;
    private final ToolDefinition definition;

    McpToolCallback(Session session, Tool tool) {
        this.session = session;
        this.definition = DefaultToolDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(JSON.writeValueAsString(tool.inputSchema()))
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String call(String toolInput) {
        Map<String, Object> arguments =
                toolInput == null || toolInput.isBlank() ? Map.of() : JSON.readValue(toolInput, Map.class);
        // Errors go back as text too: the message is the only thing the model can act on.
        try {
            return session.call(definition.name(), arguments).text();
        } catch (RuntimeException e) {
            return "The tool call failed: " + e.getMessage();
        }
    }
}
