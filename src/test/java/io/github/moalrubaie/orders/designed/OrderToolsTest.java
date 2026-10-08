package io.github.moalrubaie.orders.designed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.moalrubaie.orders.designed.Results.CancelReason;
import io.github.moalrubaie.orders.designed.Results.CancelResult;
import io.github.moalrubaie.orders.designed.Results.OrderState;
import io.github.moalrubaie.orders.designed.Results.OrderStatusResult;
import io.github.moalrubaie.orders.designed.Results.OrderSummary;
import io.github.moalrubaie.orders.designed.Results.SearchResult;
import io.github.moalrubaie.orders.mcp.SignedInCustomer;
import io.github.moalrubaie.orders.mcp.ToolError;
import io.github.moalrubaie.orders.store.OrderStatus;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.SeedData;
import io.modelcontextprotocol.common.McpTransportContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderToolsTest {

    private static final McpTransportContext DANA =
            McpTransportContext.create(Map.of(SignedInCustomer.KEY, SeedData.DANA));
    private static final String IN_TRANSIT = SeedData.orderIdOn(LocalDate.of(2026, 9, 29));
    private static final String LATE = SeedData.orderIdOn(LocalDate.of(2026, 10, 1));
    private static final String NOT_SHIPPED = SeedData.orderIdOn(LocalDate.of(2026, 10, 4));

    private final OrderStore store = SeedData.store();
    private final OrderTools tools = new OrderTools(store);

    @Test
    void statusAnswersWhereIsMyOrderInOneSentence() throws ToolError {
        OrderStatusResult result = tools.getOrderStatus(IN_TRANSIT, DANA);
        assertThat(result.status()).isEqualTo(OrderState.SHIPPED);
        assertThat(result.summary())
                .isEqualTo("Shipped Oct 2 with CarrierX, expected Oct 6 (today), last scan Oct 5 in Pittsburgh, PA"
                        + " (Arrived at facility).");
        assertThat(result.invoiceNumber()).isEqualTo(8841);
        assertThat(result.canCancel()).isFalse();
    }

    @Test
    void aShipmentPastItsDateIsDelayed() throws ToolError {
        OrderStatusResult result = tools.getOrderStatus(LATE, DANA);
        assertThat(result.status()).isEqualTo(OrderState.DELAYED);
        assertThat(result.summary()).startsWith("Running late:").contains("Harrisburg, PA");
    }

    @Test
    void lowerCaseAndSpacesInTheIdAreFine() throws ToolError {
        assertThat(tools.getOrderStatus(" " + IN_TRANSIT.toLowerCase() + " ", DANA)
                        .orderId())
                .isEqualTo(IN_TRANSIT);
    }

    @Test
    void anInvoiceNumberGetsPointedAtSearchOrders() {
        assertThatThrownBy(() -> tools.getOrderStatus("8841", DANA))
                .isInstanceOf(ToolError.class)
                .hasMessage("order_id must look like ORD-12345; you sent \"8841\", which looks like an invoice number."
                        + " Call search_orders with invoice_number=8841 to find the order ID.");
    }

    @Test
    void someoneElsesOrderLooksLikeAMissingOne() {
        String someoneElses = "ORD-10003";
        String missing = "ORD-99999";
        assertThatThrownBy(() -> tools.getOrderStatus(someoneElses, DANA))
                .hasMessage("There is no order ORD-10003 on this customer's account. Check the ID with the customer,"
                        + " or call search_orders to list their orders.");
        assertThatThrownBy(() -> tools.getOrderStatus(missing, DANA))
                .hasMessage("There is no order ORD-99999 on this customer's account. Check the ID with the customer,"
                        + " or call search_orders to list their orders.");
    }

    @Test
    void nothingWorksWithoutASignedInCustomer() {
        assertThatThrownBy(() -> tools.searchOrders(null, null, null, null, null, McpTransportContext.EMPTY))
                .hasMessageStartingWith("No customer is signed in on this connection");
    }

    @Test
    void searchByInvoiceNumber() throws ToolError {
        SearchResult result = tools.searchOrders(null, null, null, 8841, null, DANA);
        assertThat(result.orders()).extracting(OrderSummary::orderId).containsExactly(IN_TRANSIT);
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    void searchByStatusUsesTheCustomersWordForIt() throws ToolError {
        assertThat(tools.searchOrders(OrderState.DELAYED, null, null, null, null, DANA)
                        .orders())
                .extracting(OrderSummary::orderId)
                .containsExactly(LATE);
    }

    @Test
    void searchByDateRange() throws ToolError {
        SearchResult result = tools.searchOrders(null, "2026-09-01", "2026-09-30", null, null, DANA);
        assertThat(result.totalMatches()).isEqualTo(5);
        assertThat(result.orders()).extracting(OrderSummary::placedOn).isSortedAccordingTo((a, b) -> b.compareTo(a));
    }

    @Test
    void aRelativeDateGetsTodaysDateBack() {
        assertThatThrownBy(() -> tools.searchOrders(null, "last month", null, null, null, DANA))
                .hasMessage("placed_after must be a date like 2026-09-01; you sent \"last month\". Today is 2026-10-06,"
                        + " so work relative dates out from that.");
    }

    @Test
    void datesTheWrongWayRound() {
        assertThatThrownBy(() -> tools.searchOrders(null, "2026-09-30", "2026-09-01", null, null, DANA))
                .hasMessageStartingWith("placed_after (2026-09-30) is later than placed_before (2026-09-01)");
    }

    @Test
    void pagesFollowTheCursorToTheEnd() throws ToolError {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            SearchResult page = tools.searchOrders(null, null, null, null, cursor, DANA);
            assertThat(page.orders()).hasSizeLessThanOrEqualTo(OrderTools.PAGE_SIZE);
            assertThat(page.totalMatches()).isEqualTo(10);
            page.orders().forEach(o -> seen.add(o.orderId()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(seen).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void aMadeUpCursorIsRejected() {
        for (String cursor : List.of("page-2", encode("offset:-5"), encode("whatever:5"))) {
            assertThatThrownBy(() -> tools.searchOrders(null, null, null, null, cursor, DANA))
                    .hasMessageStartingWith("cursor \"" + cursor + "\" isn't one this tool gave out.");
        }
    }

    @Test
    void aCursorPastTheEndIsAnEmptyPage() throws ToolError {
        SearchResult page = tools.searchOrders(null, null, null, null, encode("offset:2147483647"), DANA);
        assertThat(page.orders()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    private static String encode(String cursor) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void cancelPreviewsFirstAndChangesNothing() throws ToolError {
        CancelResult preview = tools.cancelOrder(NOT_SHIPPED, CancelReason.WRONG_ITEM, null, DANA);
        assertThat(preview.cancelled()).isFalse();
        assertThat(preview.message())
                .startsWith("Not cancelled yet. This will cancel " + NOT_SHIPPED)
                .contains("Visa ending 4417")
                .endsWith("call cancel_order again with confirm=true.");
        assertThat(store.order(NOT_SHIPPED).orElseThrow().status()).isEqualTo(OrderStatus.PLACED);

        CancelResult done = tools.cancelOrder(NOT_SHIPPED, CancelReason.WRONG_ITEM, true, DANA);
        assertThat(done.cancelled()).isTrue();
        assertThat(store.order(NOT_SHIPPED).orElseThrow().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(store.order(NOT_SHIPPED).orElseThrow().cancelReason()).isEqualTo("WRONG_ITEM");
    }

    @Test
    void cancellingTwiceIsHarmless() throws ToolError {
        tools.cancelOrder(NOT_SHIPPED, CancelReason.OTHER, true, DANA);
        CancelResult again = tools.cancelOrder(NOT_SHIPPED, CancelReason.OTHER, true, DANA);
        assertThat(again.cancelled()).isTrue();
        assertThat(again.message()).endsWith("Nothing changed.");
    }

    @Test
    void aShippedOrderCantBeCancelledAndTheErrorSaysWhatToOffer() {
        assertThatThrownBy(() -> tools.cancelOrder(IN_TRANSIT, CancelReason.CHANGED_MIND, true, DANA))
                .hasMessage(IN_TRANSIT + " has already shipped (CarrierX, Oct 2), so it can't be cancelled. Tell the"
                        + " customer they can return it once it arrives (expected Oct 6).");
        assertThat(store.order(IN_TRANSIT).orElseThrow().status()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void cannotCancelSomeoneElsesOrder() {
        String someoneElses = store.orders().stream()
                .filter(o -> !o.customerId().equals(SeedData.DANA) && o.status() == OrderStatus.PLACED)
                .findFirst()
                .orElseThrow()
                .id();
        assertThatThrownBy(() -> tools.cancelOrder(someoneElses, CancelReason.OTHER, true, DANA))
                .hasMessageStartingWith("There is no order " + someoneElses);
        assertThat(store.order(someoneElses).orElseThrow().status()).isEqualTo(OrderStatus.PLACED);
    }
}
