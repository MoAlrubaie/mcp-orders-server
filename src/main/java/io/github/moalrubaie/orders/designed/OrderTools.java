package io.github.moalrubaie.orders.designed;

import io.github.moalrubaie.orders.designed.Results.CancelReason;
import io.github.moalrubaie.orders.designed.Results.CancelResult;
import io.github.moalrubaie.orders.designed.Results.LastScan;
import io.github.moalrubaie.orders.designed.Results.OrderState;
import io.github.moalrubaie.orders.designed.Results.OrderStatusResult;
import io.github.moalrubaie.orders.designed.Results.OrderSummary;
import io.github.moalrubaie.orders.designed.Results.SearchResult;
import io.github.moalrubaie.orders.mcp.SignedInCustomer;
import io.github.moalrubaie.orders.mcp.ToolError;
import io.github.moalrubaie.orders.store.Order;
import io.github.moalrubaie.orders.store.OrderStatus;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.Shipment;
import io.modelcontextprotocol.common.McpTransportContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The same order service, exposed as three tools shaped around what a customer asks: "where is
 * my order?", "which of my orders...?" and "cancel this one". Each tool does the lookups the
 * question needs and returns a small answer.
 *
 * <p>Parameter names are snake_case Java names on purpose: they become the argument names the
 * model sees.
 */
public class OrderTools {

    static final int PAGE_SIZE = 5;
    private static final Pattern ORDER_ID = Pattern.compile("ORD-\\d{5}");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private final OrderStore store;

    public OrderTools(OrderStore store) {
        this.store = store;
    }

    @McpTool(
            name = "get_order_status",
            title = "Get order status",
            description = """
                    Get where one order is right now: its status, carrier, expected delivery date and \
                    latest tracking scan, in one call. Use it when the customer asks where an order is, \
                    whether it has shipped, or when it will arrive. Needs the order ID (ORD-12345). If \
                    the customer only has an invoice number, a date or a status, call search_orders \
                    first to find the order ID.""",
            annotations =
                    @McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false),
            generateOutputSchema = true)
    public OrderStatusResult getOrderStatus(
            @McpToolParam(
                            description = "Order ID in the form ORD-12345, as shown on the order confirmation. Not the"
                                    + " numeric invoice number.")
                    String order_id,
            McpTransportContext context)
            throws ToolError {
        Order order = customersOrder(order_id, customer(context));
        Optional<Shipment> shipment = shipment(order);
        OrderState state = state(order, shipment);
        Shipment s = shipment.orElse(null);
        return new OrderStatusResult(
                order.id(),
                order.invoiceNumber(),
                state,
                summary(order, state, s),
                order.items().stream()
                        .map(i -> productName(i.sku()) + (i.quantity() > 1 ? " x" + i.quantity() : ""))
                        .toList(),
                order.placedOn(),
                s == null ? null : s.carrier(),
                s == null ? null : s.trackingNumber(),
                s == null ? null : s.shippedOn(),
                s == null || s.deliveredOn() != null ? null : s.expectedOn(),
                s == null ? null : s.deliveredOn(),
                s == null
                        ? null
                        : new LastScan(
                                s.lastScan().on(),
                                s.lastScan().location(),
                                s.lastScan().description()),
                cancellable(order));
    }

    @McpTool(
            name = "search_orders",
            title = "Search orders",
            description = """
                    Find the signed-in customer's orders by status, date range or invoice number. \
                    Returns short summaries (order ID, date, status, items, total), newest first, 5 per \
                    page. Use it when the customer has no order ID, or asks about several orders, like \
                    "what did I order in September?" or "are any of my orders late?". With no filters it \
                    lists all of their orders. For tracking details of one order, call get_order_status \
                    with its order_id.""",
            annotations =
                    @McpAnnotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false),
            generateOutputSchema = true)
    public SearchResult searchOrders(
            @McpToolParam(
                            required = false,
                            description = "Only orders in this state. DELAYED means shipped but past its expected"
                                    + " delivery date (\"late\"). SHIPPED means on its way and on time.")
                    OrderState status,
            @McpToolParam(
                            required = false,
                            description = "Only orders placed on or after this date, as YYYY-MM-DD, like 2026-09-01.")
                    String placed_after,
            @McpToolParam(
                            required = false,
                            description = "Only orders placed on or before this date, as YYYY-MM-DD, like 2026-09-30.")
                    String placed_before,
            @McpToolParam(
                            required = false,
                            description =
                                    "The invoice number from the customer's receipt or card statement, like 8841.")
                    Integer invoice_number,
            @McpToolParam(
                            required = false,
                            description = "The next_cursor value from a previous search_orders result, to get the next"
                                    + " page. Leave it out for the first page.")
                    String cursor,
            McpTransportContext context)
            throws ToolError {
        String customerId = customer(context);
        LocalDate after = date("placed_after", placed_after);
        LocalDate before = date("placed_before", placed_before);
        if (after != null && before != null && after.isAfter(before)) {
            throw new ToolError("placed_after (%s) is later than placed_before (%s). Swap them, or drop one."
                    .formatted(after, before));
        }
        List<Order> matches = store.ordersFor(customerId).stream()
                .filter(o -> status == null || state(o, shipment(o)) == status)
                .filter(o -> after == null || !o.placedOn().isBefore(after))
                .filter(o -> before == null || !o.placedOn().isAfter(before))
                .filter(o -> invoice_number == null || o.invoiceNumber() == invoice_number)
                .sorted(Comparator.comparing(Order::placedOn).reversed())
                .toList();
        int from = Math.min(offset(cursor), matches.size());
        int to = Math.min(from + PAGE_SIZE, matches.size());
        List<OrderSummary> page = matches.subList(from, to).stream()
                .map(o -> new OrderSummary(
                        o.id(),
                        o.invoiceNumber(),
                        o.placedOn(),
                        state(o, shipment(o)),
                        String.join(
                                "; ",
                                o.items().stream()
                                        .map(i -> productName(i.sku()) + (i.quantity() > 1 ? " x" + i.quantity() : ""))
                                        .toList()),
                        money(o.total())))
                .toList();
        return new SearchResult(page, matches.size(), to < matches.size() ? cursorFor(to) : null);
    }

    @McpTool(
            name = "cancel_order",
            title = "Cancel order",
            description = """
                    Cancel one of the signed-in customer's orders that hasn't shipped yet, and refund \
                    it. Use it when the customer asks to cancel an order. Call it first without confirm: nothing changes, and the result says exactly \
                    what would be cancelled and refunded. Tell the customer, and only after they agree, \
                    call it again with confirm=true. Orders that have shipped can't be cancelled; the \
                    error says what to offer instead. Safe to repeat: cancelling an order that is \
                    already cancelled changes nothing.""",
            annotations =
                    @McpAnnotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = true,
                            openWorldHint = false),
            generateOutputSchema = true)
    public CancelResult cancelOrder(
            @McpToolParam(description = "Order ID in the form ORD-12345. Not the numeric invoice number.")
                    String order_id,
            @McpToolParam(description = "Why the customer is cancelling.") CancelReason reason,
            @McpToolParam(
                            required = false,
                            description =
                                    "Leave it out (or false) to preview the cancellation. Set it to true only after"
                                            + " the customer has agreed to the preview.")
                    Boolean confirm,
            McpTransportContext context)
            throws ToolError {
        Order order = customersOrder(order_id, customer(context));
        if (order.status() == OrderStatus.CANCELLED) {
            return new CancelResult(
                    order.id(),
                    true,
                    OrderState.CANCELLED,
                    "%s was already cancelled on %s. Nothing changed."
                            .formatted(order.id(), DAY.format(order.cancelledOn())));
        }
        if (!cancellable(order)) {
            Shipment s = shipment(order).orElseThrow();
            throw new ToolError(
                    s.deliveredOn() != null
                            ? "%s was delivered on %s, so it can't be cancelled. Tell the customer they can return it instead; returns are handled by the support team."
                                    .formatted(order.id(), DAY.format(s.deliveredOn()))
                            : "%s has already shipped (%s, %s), so it can't be cancelled. Tell the customer they can return it once it arrives (expected %s)."
                                    .formatted(
                                            order.id(),
                                            s.carrier(),
                                            DAY.format(s.shippedOn()),
                                            DAY.format(s.expectedOn())));
        }
        if (reason == null) {
            throw new ToolError("reason is required. Ask the customer why they are cancelling if it isn't clear;"
                    + " use OTHER if they'd rather not say.");
        }
        String what = "%s (%s), refunding %s to %s"
                .formatted(
                        order.id(),
                        String.join(
                                ", ",
                                order.items().stream()
                                        .map(i -> productName(i.sku()))
                                        .toList()),
                        money(order.total()),
                        order.paymentMethod());
        if (!Boolean.TRUE.equals(confirm)) {
            return new CancelResult(
                    order.id(),
                    false,
                    state(order, Optional.empty()),
                    "Not cancelled yet. This will cancel " + what
                            + ". Check with the customer, then call cancel_order again with confirm=true.");
        }
        Order after = store.cancel(order.id(), reason.name()).orElseThrow();
        if (after.status() != OrderStatus.CANCELLED) {
            throw new ToolError(
                    "%s shipped while this was being cancelled, so it wasn't cancelled. Call".formatted(order.id())
                            + " get_order_status to see where it is.");
        }
        return new CancelResult(order.id(), true, OrderState.CANCELLED, "Cancelled " + what + ".");
    }

    // --- Checks that live in the server, whatever the model asks for ---

    private static String customer(McpTransportContext context) throws ToolError {
        String id = SignedInCustomer.from(context);
        if (id == null) {
            throw new ToolError("No customer is signed in on this connection, so there are no orders to look up."
                    + " Ask the user to sign in to the app first.");
        }
        return id;
    }

    /** Someone else's order looks exactly like a missing one: no hint that it exists. */
    private Order customersOrder(String orderId, String customerId) throws ToolError {
        String id = orderId == null ? "" : orderId.strip().toUpperCase(Locale.ROOT);
        if (!ORDER_ID.matcher(id).matches()) {
            throw new ToolError(
                    id.matches("\\d+")
                            ? ("order_id must look like ORD-12345; you sent \"%s\", which looks like an invoice"
                                            + " number. Call search_orders with invoice_number=%s to find the order ID.")
                                    .formatted(orderId, id)
                            : ("order_id must look like ORD-12345; you sent \"%s\". If you don't have the order ID,"
                                            + " call search_orders to find it.")
                                    .formatted(orderId));
        }
        return store.order(id)
                .filter(o -> o.customerId().equals(customerId))
                .orElseThrow(() -> new ToolError(
                        ("There is no order %s on this customer's account. Check the ID with the customer, or call"
                                        + " search_orders to list their orders.")
                                .formatted(id)));
    }

    private LocalDate date(String name, String value) throws ToolError {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new ToolError(
                    "%s must be a date like 2026-09-01; you sent \"%s\". Today is %s, so work relative dates out from that."
                            .formatted(name, value, store.today()));
        }
    }

    private static int offset(String cursor) throws ToolError {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor.strip()), StandardCharsets.UTF_8);
            if (decoded.startsWith("offset:")) {
                int offset = Integer.parseInt(decoded.substring("offset:".length()));
                if (offset >= 0) {
                    return offset;
                }
            }
        } catch (RuntimeException e) {
            // fall through to the same message
        }
        throw new ToolError("cursor \"%s\" isn't one this tool gave out. Leave it out to start from the first page."
                .formatted(cursor));
    }

    private static String cursorFor(int offset) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(("offset:" + offset).getBytes(StandardCharsets.UTF_8));
    }

    // --- Turning records into answers ---

    private Optional<Shipment> shipment(Order order) {
        return store.shipmentsFor(order.id()).stream().findFirst();
    }

    private OrderState state(Order order, Optional<Shipment> shipment) {
        return switch (order.status()) {
            case PLACED -> OrderState.PLACED;
            case PACKED -> OrderState.PACKED;
            case DELIVERED -> OrderState.DELIVERED;
            case CANCELLED -> OrderState.CANCELLED;
            case SHIPPED ->
                shipment.filter(s -> s.isLate(store.today())).isPresent() ? OrderState.DELAYED : OrderState.SHIPPED;
        };
    }

    private static boolean cancellable(Order order) {
        return order.status() == OrderStatus.PLACED || order.status() == OrderStatus.PACKED;
    }

    private String summary(Order order, OrderState state, Shipment s) {
        return switch (state) {
            case PLACED ->
                "Placed %s, not shipped yet. It can still be cancelled.".formatted(DAY.format(order.placedOn()));
            case PACKED -> "Packed and waiting for the carrier. It can still be cancelled.";
            case CANCELLED -> "Cancelled on %s. Nothing was shipped.".formatted(DAY.format(order.cancelledOn()));
            case DELIVERED -> "Delivered %s (%s).".formatted(DAY.format(s.deliveredOn()), s.carrier());
            case SHIPPED ->
                "Shipped %s with %s, expected %s%s, last scan %s in %s (%s)."
                        .formatted(
                                DAY.format(s.shippedOn()),
                                s.carrier(),
                                DAY.format(s.expectedOn()),
                                s.expectedOn().equals(store.today()) ? " (today)" : "",
                                DAY.format(s.lastScan().on()),
                                s.lastScan().location(),
                                s.lastScan().description());
            case DELAYED ->
                "Running late: shipped %s with %s, expected %s, last scan %s in %s (%s)."
                        .formatted(
                                DAY.format(s.shippedOn()),
                                s.carrier(),
                                DAY.format(s.expectedOn()),
                                DAY.format(s.lastScan().on()),
                                s.lastScan().location(),
                                s.lastScan().description());
        };
    }

    private String productName(String sku) {
        return store.product(sku).map(p -> p.name()).orElse(sku);
    }

    private static String money(BigDecimal amount) {
        return "$" + amount.setScale(2);
    }
}
