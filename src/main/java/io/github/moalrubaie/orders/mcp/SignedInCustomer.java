package io.github.moalrubaie.orders.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import java.util.Map;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who is on the other end of the connection, decided by the server, never by the model.
 *
 * <p>This demo reads a customer ID from the {@value #HEADER} request header and puts it in the
 * MCP transport context, where tools can read it. That header is a stand-in: a real remote server
 * gets the user from an OAuth access token (the MCP authorization spec), not from a header any
 * client can set.
 */
@Configuration(proxyBeanMethods = false)
public class SignedInCustomer {

    public static final String HEADER = "X-Demo-Customer";
    public static final String KEY = "customerId";

    /** Replaces Spring AI's default Streamable HTTP transport, adding the context extractor. */
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper, McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(request -> request.headers().header(HEADER).stream()
                        .findFirst()
                        .map(id -> McpTransportContext.create(Map.of(KEY, id.strip())))
                        .orElse(McpTransportContext.EMPTY))
                .build();
    }

    /** The signed-in customer's ID, or null when the connection didn't say. */
    public static String from(McpTransportContext context) {
        return context == null ? null : (String) context.get(KEY);
    }
}
