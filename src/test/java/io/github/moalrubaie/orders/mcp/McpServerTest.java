package io.github.moalrubaie.orders.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.moalrubaie.orders.demo.Servers;
import io.github.moalrubaie.orders.store.SeedData;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Both servers over real Streamable HTTP, through the MCP Java SDK client. */
class McpServerTest {

    private static Servers.Running naive;
    private static Servers.Running designed;

    @BeforeAll
    static void start() {
        naive = Servers.start("naive");
        designed = Servers.start("designed");
    }

    @AfterAll
    static void stop() {
        naive.close();
        designed.close();
    }

    @Test
    void theNaiveServerHasOneToolPerEndpoint() {
        try (McpSyncClient client = connect(naive.url(), null)) {
            assertThat(client.listTools().tools())
                    .extracting(Tool::name)
                    .hasSize(15)
                    .contains("getOrders", "getOrdersById", "getInvoicesById", "deleteOrdersById", "getCustomers");
        }
    }

    @Test
    void theDesignedServerHasThreeAnnotatedTools() {
        try (McpSyncClient client = connect(designed.url(), SeedData.DANA)) {
            List<Tool> tools = client.listTools().tools();
            assertThat(tools)
                    .extracting(Tool::name)
                    .containsExactly("cancel_order", "get_order_status", "search_orders");
            for (Tool tool : tools) {
                assertThat(tool.outputSchema()).as(tool.name()).isNotNull();
                boolean reads = !tool.name().equals("cancel_order");
                assertThat(tool.annotations().readOnlyHint()).as(tool.name()).isEqualTo(reads);
                assertThat(tool.annotations().destructiveHint()).as(tool.name()).isEqualTo(!reads);
            }
            assertThat(client.getServerInstructions()).contains("signed-in customer");
        }
    }

    @Test
    void aDesignedResultIsStructuredAndAlsoText() {
        try (McpSyncClient client = connect(designed.url(), SeedData.DANA)) {
            CallToolResult result = call(client, "search_orders", Map.of("invoice_number", 8841));
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.structuredContent()).isInstanceOf(Map.class);
            assertThat(text(result)).contains("\"invoice_number\":8841");
        }
    }

    @Test
    void aToolErrorIsAnErrorResultWithJustTheMessage() {
        try (McpSyncClient client = connect(designed.url(), SeedData.DANA)) {
            CallToolResult result = call(client, "get_order_status", Map.of("order_id", "8841"));
            assertThat(result.isError()).isTrue();
            assertThat(text(result))
                    .isEqualTo("order_id must look like ORD-12345; you sent \"8841\", which looks like an invoice"
                            + " number. Call search_orders with invoice_number=8841 to find the order ID.");
        }
    }

    @Test
    void anEnumValueOutsideTheSchemaIsRejectedBeforeTheToolRuns() {
        try (McpSyncClient client = connect(designed.url(), SeedData.DANA)) {
            CallToolResult result = call(client, "search_orders", Map.of("status", "late"));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("DELAYED");
        }
    }

    @Test
    void theCustomerComesFromTheConnectionNotTheArguments() {
        try (McpSyncClient dana = connect(designed.url(), SeedData.DANA);
                McpSyncClient nobody = connect(designed.url(), null)) {
            assertThat(call(dana, "search_orders", Map.of()).isError()).isNotEqualTo(Boolean.TRUE);
            CallToolResult anonymous = call(nobody, "search_orders", Map.of());
            assertThat(anonymous.isError()).isTrue();
            assertThat(text(anonymous)).startsWith("No customer is signed in");
        }
    }

    @Test
    void theNaiveServerAnswersWithStatusCodes() {
        try (McpSyncClient client = connect(naive.url(), null)) {
            CallToolResult result = call(client, "getOrdersById", Map.of("id", "8841"));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("Error 404: Not Found");
        }
    }

    private static McpSyncClient connect(String url, String customerId) {
        HttpRequest.Builder request = HttpRequest.newBuilder();
        if (customerId != null) {
            request.header(SignedInCustomer.HEADER, customerId);
        }
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(url)
                        .endpoint("/mcp")
                        .requestBuilder(request)
                        .build())
                .build();
        client.initialize();
        return client;
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(
                CallToolRequest.builder(tool).arguments(arguments).build());
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }
}
