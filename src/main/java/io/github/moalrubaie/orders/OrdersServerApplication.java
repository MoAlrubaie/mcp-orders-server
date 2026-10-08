package io.github.moalrubaie.orders;

import io.github.moalrubaie.orders.designed.OrderTools;
import io.github.moalrubaie.orders.mcp.ToolSpecs;
import io.github.moalrubaie.orders.naive.NaiveOrderApi;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.SeedData;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * One order service, two MCP servers. Start it with the {@code designed} profile (the default,
 * port 8080) or the {@code naive} profile (port 8081). Both serve Streamable HTTP at {@code /mcp}.
 */
@SpringBootApplication
public class OrdersServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersServerApplication.class, args);
    }

    @Bean
    OrderStore orderStore() {
        return SeedData.store();
    }

    @Bean
    @Profile("naive")
    List<SyncToolSpecification> naiveTools(OrderStore store) {
        return ToolSpecs.of(new NaiveOrderApi(store));
    }

    @Bean
    @Profile("designed")
    List<SyncToolSpecification> designedTools(OrderStore store) {
        return ToolSpecs.of(new OrderTools(store));
    }
}
