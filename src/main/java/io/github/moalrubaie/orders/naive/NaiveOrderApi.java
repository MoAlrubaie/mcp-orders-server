package io.github.moalrubaie.orders.naive;

import io.github.moalrubaie.orders.mcp.ToolError;
import io.github.moalrubaie.orders.naive.Resources.CustomerResource;
import io.github.moalrubaie.orders.naive.Resources.InvoiceResource;
import io.github.moalrubaie.orders.naive.Resources.ItemResource;
import io.github.moalrubaie.orders.naive.Resources.OrderResource;
import io.github.moalrubaie.orders.naive.Resources.PageResource;
import io.github.moalrubaie.orders.naive.Resources.ProductResource;
import io.github.moalrubaie.orders.naive.Resources.ShipmentResource;
import io.github.moalrubaie.orders.naive.Resources.TrackingResource;
import io.github.moalrubaie.orders.store.Order;
import io.github.moalrubaie.orders.store.OrderStatus;
import io.github.moalrubaie.orders.store.OrderStore;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The order service's REST API, one tool per endpoint: what you get from pointing an
 * OpenAPI-to-MCP generator at the API. Names are the operation IDs, descriptions are the
 * endpoint summaries, results are the full REST representations, and errors are status codes.
 *
 * <p>It works. Every call does what the endpoint does. It's here to show what that costs an agent.
 */
public class NaiveOrderApi {

    private static final String NOT_FOUND = "Error 404: Not Found";
    private static final String BAD_REQUEST = "Error 400: Bad Request";
    private static final String INVALID = "Error 422: invalid input";
    private static final String CONFLICT = "Error 409: Conflict";

    private final OrderStore store;

    public NaiveOrderApi(OrderStore store) {
        this.store = store;
    }

    @McpTool(name = "getOrders", description = "List orders")
    public PageResource<OrderResource> getOrders(
            @McpToolParam(required = false, description = "Filter by customer") String customerId,
            @McpToolParam(required = false, description = "Filter by status") String status,
            @McpToolParam(required = false, description = "Page number") Integer page,
            @McpToolParam(required = false, description = "Page size") Integer size)
            throws ToolError {
        OrderStatus wanted = status == null ? null : parseStatus(status);
        List<OrderResource> orders = store.orders().stream()
                .filter(o -> customerId == null || o.customerId().equals(customerId))
                .filter(o -> wanted == null || o.status() == wanted)
                .map(o -> Resources.order(o, store))
                .toList();
        return Resources.page(orders, pageOf(page), sizeOf(size), "/orders");
    }

    @McpTool(name = "getOrdersById", description = "Get order by ID")
    public OrderResource getOrdersById(String id) throws ToolError {
        return Resources.order(order(id), store);
    }

    @McpTool(name = "patchOrdersById", description = "Update order")
    public OrderResource patchOrdersById(String id, Map<String, Object> body) throws ToolError {
        Order o = order(id);
        if (body == null || body.isEmpty() || !body.keySet().stream().allMatch(k -> k.equals("customerNote"))) {
            throw new ToolError(INVALID);
        }
        Order updated = store.update(o.id(), order -> order.withCustomerNote(String.valueOf(body.get("customerNote"))))
                .orElseThrow(() -> new ToolError(NOT_FOUND));
        return Resources.order(updated, store);
    }

    @McpTool(name = "deleteOrdersById", description = "Delete order")
    public String deleteOrdersById(String id) throws ToolError {
        store.delete(order(id).id());
        return "204 No Content";
    }

    @McpTool(name = "postOrdersByIdCancel", description = "Cancel order")
    public OrderResource postOrdersByIdCancel(String id) throws ToolError {
        Order o = order(id);
        if (o.status() != OrderStatus.PLACED && o.status() != OrderStatus.PACKED) {
            throw new ToolError(CONFLICT);
        }
        Order cancelled = store.cancel(o.id(), "API").orElseThrow(() -> new ToolError(NOT_FOUND));
        if (cancelled.status() != OrderStatus.CANCELLED) {
            throw new ToolError(CONFLICT); // it shipped in the meantime
        }
        return Resources.order(cancelled, store);
    }

    @McpTool(name = "getOrdersByIdItems", description = "Get order items")
    public List<ItemResource> getOrdersByIdItems(String id) throws ToolError {
        return Resources.items(order(id), store);
    }

    @McpTool(name = "getOrdersByIdShipments", description = "Get order shipments")
    public List<ShipmentResource> getOrdersByIdShipments(String id) throws ToolError {
        return store.shipmentsFor(order(id).id()).stream()
                .map(Resources::shipment)
                .toList();
    }

    @McpTool(name = "getShipmentsById", description = "Get shipment by ID")
    public ShipmentResource getShipmentsById(String id) throws ToolError {
        return Resources.shipment(store.shipment(id).orElseThrow(() -> new ToolError(NOT_FOUND)));
    }

    @McpTool(name = "getShipmentsByIdTracking", description = "Get shipment tracking")
    public TrackingResource getShipmentsByIdTracking(String id) throws ToolError {
        return Resources.tracking(store.shipment(id).orElseThrow(() -> new ToolError(NOT_FOUND)));
    }

    @McpTool(name = "getCustomers", description = "List customers")
    public PageResource<CustomerResource> getCustomers(
            @McpToolParam(required = false, description = "Filter by email") String email,
            @McpToolParam(required = false, description = "Page number") Integer page,
            @McpToolParam(required = false, description = "Page size") Integer size)
            throws ToolError {
        List<CustomerResource> customers = store.customers().stream()
                .filter(c -> email == null || c.email().equalsIgnoreCase(email))
                .map(Resources::customer)
                .toList();
        return Resources.page(customers, pageOf(page), sizeOf(size), "/customers");
    }

    @McpTool(name = "getCustomersById", description = "Get customer by ID")
    public CustomerResource getCustomersById(String id) throws ToolError {
        return Resources.customer(store.customer(id).orElseThrow(() -> new ToolError(NOT_FOUND)));
    }

    @McpTool(name = "getCustomersByIdOrders", description = "Get customer orders")
    public PageResource<OrderResource> getCustomersByIdOrders(
            String id,
            @McpToolParam(required = false, description = "Page number") Integer page,
            @McpToolParam(required = false, description = "Page size") Integer size)
            throws ToolError {
        store.customer(id).orElseThrow(() -> new ToolError(NOT_FOUND));
        List<OrderResource> orders =
                store.ordersFor(id).stream().map(o -> Resources.order(o, store)).toList();
        return Resources.page(orders, pageOf(page), sizeOf(size), "/customers/" + id + "/orders");
    }

    @McpTool(name = "getInvoicesById", description = "Get invoice by ID")
    public InvoiceResource getInvoicesById(String id) throws ToolError {
        int number;
        try {
            number = Integer.parseInt(id.strip());
        } catch (NumberFormatException e) {
            throw new ToolError(BAD_REQUEST);
        }
        return Resources.invoice(store.orderByInvoice(number).orElseThrow(() -> new ToolError(NOT_FOUND)), store);
    }

    @McpTool(name = "getProducts", description = "List products")
    public PageResource<ProductResource> getProducts(
            @McpToolParam(required = false, description = "Filter by category") String category,
            @McpToolParam(required = false, description = "Page number") Integer page,
            @McpToolParam(required = false, description = "Page size") Integer size)
            throws ToolError {
        List<ProductResource> products = store.products().stream()
                .filter(p -> category == null || p.category().equalsIgnoreCase(category))
                .map(Resources::product)
                .toList();
        return Resources.page(products, pageOf(page), sizeOf(size), "/products");
    }

    @McpTool(name = "getProductsById", description = "Get product by ID")
    public ProductResource getProductsById(String id) throws ToolError {
        return Resources.product(store.product(id).orElseThrow(() -> new ToolError(NOT_FOUND)));
    }

    private Order order(String id) throws ToolError {
        if (id == null || id.isBlank()) {
            throw new ToolError(BAD_REQUEST);
        }
        return store.order(id.strip()).orElseThrow(() -> new ToolError(NOT_FOUND));
    }

    private static OrderStatus parseStatus(String status) throws ToolError {
        return Arrays.stream(OrderStatus.values())
                .filter(s -> s.name().equals(status))
                .findFirst()
                .orElseThrow(() -> new ToolError(BAD_REQUEST));
    }

    private static int pageOf(Integer page) throws ToolError {
        if (page != null && page < 0) {
            throw new ToolError(BAD_REQUEST);
        }
        return page == null ? 0 : page;
    }

    private static int sizeOf(Integer size) throws ToolError {
        if (size != null && (size < 1 || size > 200)) {
            throw new ToolError(BAD_REQUEST);
        }
        return size == null ? 50 : size;
    }
}
