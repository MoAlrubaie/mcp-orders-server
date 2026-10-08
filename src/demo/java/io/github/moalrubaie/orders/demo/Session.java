package io.github.moalrubaie.orders.demo;

import io.github.moalrubaie.orders.mcp.SignedInCustomer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An MCP client connection that records every tool call and the text that came back, which is
 * exactly what a model would have to read.
 */
public final class Session implements AutoCloseable {

    public record Call(String tool, Map<String, Object> arguments, boolean error, String text) {

        public int tokens() {
            return Tokens.of(text);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpSyncClient client;
    private final List<Call> calls = new ArrayList<>();

    private Session(McpSyncClient client) {
        this.client = client;
    }

    /** Connects to a server; {@code customerId} plays the part of the signed-in user, or null. */
    public static Session connect(String baseUrl, String customerId) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (customerId != null) {
            request.header(SignedInCustomer.HEADER, customerId);
        }
        var transport = HttpClientStreamableHttpTransport.builder(baseUrl)
                .endpoint("/mcp")
                .requestBuilder(request)
                .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return new Session(client);
    }

    public List<Tool> tools() {
        List<Tool> tools = new ArrayList<>();
        ListToolsResult page = client.listTools();
        tools.addAll(page.tools());
        while (page.nextCursor() != null) {
            page = client.listTools(page.nextCursor());
            tools.addAll(page.tools());
        }
        return tools;
    }

    public String instructions() {
        return client.getServerInstructions();
    }

    public Call call(String tool, Map<String, Object> arguments) {
        CallToolResult result = client.callTool(
                CallToolRequest.builder(tool).arguments(arguments).build());
        String text = result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(c -> ((TextContent) c).text())
                .collect(Collectors.joining("\n"));
        Call call = new Call(tool, arguments, Boolean.TRUE.equals(result.isError()), text);
        calls.add(call);
        return call;
    }

    /** Calls a tool that is expected to succeed and parses its JSON text. */
    public JsonNode json(String tool, Map<String, Object> arguments) {
        Call call = call(tool, arguments);
        if (call.error()) {
            throw new IllegalStateException(tool + " failed: " + call.text());
        }
        return JSON.readTree(call.text());
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public void forget() {
        calls.clear();
    }

    @Override
    public void close() {
        client.closeGracefully();
    }
}
