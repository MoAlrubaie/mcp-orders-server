package io.github.moalrubaie.orders.naive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moalrubaie.orders.mcp.ToolError;
import io.github.moalrubaie.orders.store.OrderStatus;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.SeedData;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The naive server isn't broken: every endpoint does what it says. */
class NaiveOrderApiTest {

    private final OrderStore store = SeedData.store();
    private final NaiveOrderApi api = new NaiveOrderApi(store);

    @Test
    void listsFiftyOrdersAPageByDefault() throws ToolError {
        var page = api.getOrders(null, null, null, null);
        assertThat(page.content()).hasSize(50);
        assertThat(page.totalElements()).isEqualTo(600);
        assertThat(page.links()).containsKey("next");
    }

    @Test
    void anOrderComesWithEverythingAttached() throws ToolError {
        var order = api.getOrdersById("ORD-10003");
        assertThat(order.customer().email()).endsWith("@example.com");
        assertThat(order.customerNote()).isEqualTo(SeedData.INJECTED_NOTE);
        assertThat(order.items())
                .allSatisfy(i -> assertThat(i.product().description()).isNotBlank());
        assertThat(order.links()).containsKeys("self", "customer", "items", "shipments", "invoice", "cancel");
    }

    @Test
    void theInvoiceLeadsToTheOrder() throws ToolError {
        assertThat(api.getInvoicesById("8841").orderId())
                .isEqualTo(store.orderByInvoice(8841).orElseThrow().id());
    }

    @Test
    void deleteReallyDeletes() throws ToolError {
        assertThat(api.deleteOrdersById("ORD-10001")).isEqualTo("204 No Content");
        assertThat(store.order("ORD-10001")).isEmpty();
    }

    @Test
    void patchOnlyAcceptsTheNote() throws ToolError {
        assertThat(api.patchOrdersById("ORD-10001", Map.of("customerNote", "Back door"))
                        .customerNote())
                .isEqualTo("Back door");
        assertThatThrownBy(() -> api.patchOrdersById("ORD-10001", Map.of("status", "DELIVERED")))
                .isInstanceOf(ToolError.class)
                .hasMessage("Error 422: invalid input");
    }

    @Test
    void aPageFarPastTheEndIsEmpty() throws ToolError {
        assertThat(api.getOrders(null, null, 100_000_000, 200).content()).isEmpty();
    }

    @Test
    void cancellingTwiceIsAConflict() throws ToolError {
        String open = store.orders().stream()
                .filter(o -> o.status() == OrderStatus.PLACED)
                .findFirst()
                .orElseThrow()
                .id();
        assertThat(api.postOrdersByIdCancel(open).status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> api.postOrdersByIdCancel(open)).hasMessage("Error 409: Conflict");
    }

    @Test
    void errorsAreStatusCodes() {
        assertThatThrownBy(() -> api.getOrdersById("8841")).hasMessage("Error 404: Not Found");
        assertThatThrownBy(() -> api.getOrders(null, "late", null, null)).hasMessage("Error 400: Bad Request");
        assertThatThrownBy(() -> api.getInvoicesById("INV-8841")).hasMessage("Error 400: Bad Request");
    }
}
