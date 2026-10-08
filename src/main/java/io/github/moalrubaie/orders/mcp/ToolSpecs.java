package io.github.moalrubaie.orders.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a bean's {@link McpTool} methods into MCP tool specifications, the same way Spring AI's
 * annotation scanner does, with two changes to the results:
 *
 * <ul>
 *   <li>A {@link ToolError} becomes an {@code isError} result whose text is the error message,
 *       unchanged. (Spring AI's default appends the root cause message again.)
 *   <li>A structured result also gets its JSON as a text block, as the MCP specification
 *       recommends, for clients that only read text.
 * </ul>
 */
public final class ToolSpecs {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolSpecs() {}

    public static List<SyncToolSpecification> of(Object toolBean) {
        return new SyncMcpToolProvider(List.of(toolBean))
                .getToolSpecifications().stream().map(ToolSpecs::wrap).toList();
    }

    private static SyncToolSpecification wrap(SyncToolSpecification spec) {
        return SyncToolSpecification.builder()
                .tool(spec.tool())
                .callHandler((exchange, request) -> {
                    try {
                        return withText(spec.callHandler().apply(exchange, request));
                    } catch (UndeclaredThrowableException e) {
                        if (e.getCause() instanceof ToolError error) {
                            return CallToolResult.builder()
                                    .isError(true)
                                    .addTextContent(error.getMessage())
                                    .build();
                        }
                        throw e;
                    }
                })
                .build();
    }

    private static CallToolResult withText(CallToolResult result) {
        if (result.structuredContent() == null
                || (result.content() != null && !result.content().isEmpty())) {
            return result;
        }
        return CallToolResult.builder()
                .structuredContent(result.structuredContent())
                .addTextContent(JSON.writeValueAsString(result.structuredContent()))
                .isError(result.isError())
                .build();
    }
}
