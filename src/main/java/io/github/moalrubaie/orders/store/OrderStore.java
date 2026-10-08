package io.github.moalrubaie.orders.store;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The backend both MCP servers sit on: an in-memory order system with seeded, made-up data.
 * Think of it as the company's existing order service. The servers differ only in what they
 * expose of it, and how.
 */
public class OrderStore {

    private final LocalDate today;
    private final Map<String, Customer> customers;
    private final Map<String, Product> products;
    private final Map<String, Order> orders;
    private final Map<String, Shipment> shipments;

    public OrderStore(
            LocalDate today,
            List<Customer> customers,
            List<Product> products,
            List<Order> orders,
            List<Shipment> shipments) {
        this.today = today;
        this.customers = index(customers, Customer::id);
        this.products = index(products, Product::sku);
        this.orders = new ConcurrentHashMap<>(index(orders, Order::id));
        this.shipments = index(shipments, Shipment::id);
    }

    /** The demo runs on a fixed date so every run prints the same thing. */
    public LocalDate today() {
        return today;
    }

    public Optional<Customer> customer(String id) {
        return Optional.ofNullable(customers.get(id));
    }

    public List<Customer> customers() {
        return List.copyOf(customers.values());
    }

    public Optional<Product> product(String sku) {
        return Optional.ofNullable(products.get(sku));
    }

    public List<Product> products() {
        return List.copyOf(products.values());
    }

    public Optional<Order> order(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    public Optional<Order> orderByInvoice(int invoiceNumber) {
        return orders.values().stream()
                .filter(o -> o.invoiceNumber() == invoiceNumber)
                .findFirst();
    }

    /** All orders, oldest first. */
    public List<Order> orders() {
        return sorted(orders.values());
    }

    public List<Order> ordersFor(String customerId) {
        return sorted(orders.values().stream()
                .filter(o -> o.customerId().equals(customerId))
                .toList());
    }

    public Optional<Shipment> shipment(String id) {
        return Optional.ofNullable(shipments.get(id));
    }

    public List<Shipment> shipmentsFor(String orderId) {
        return shipments.values().stream()
                .filter(s -> s.orderId().equals(orderId))
                .toList();
    }

    /**
     * Cancels the order if it is still open (placed or packed), in one atomic step. Returns the
     * order as it is afterwards, or empty if there is no such order.
     */
    public Optional<Order> cancel(String orderId, String reason) {
        return Optional.ofNullable(orders.computeIfPresent(
                orderId,
                (id, order) -> order.status() == OrderStatus.PLACED || order.status() == OrderStatus.PACKED
                        ? order.cancelled(today, reason)
                        : order));
    }

    /** Applies {@code change} atomically. Returns the updated order, or empty if there is none. */
    public Optional<Order> update(String orderId, UnaryOperator<Order> change) {
        return Optional.ofNullable(orders.computeIfPresent(orderId, (id, order) -> change.apply(order)));
    }

    public Order delete(String orderId) {
        return orders.remove(orderId);
    }

    private static List<Order> sorted(Collection<Order> orders) {
        return orders.stream().sorted(Comparator.comparing(Order::id)).toList();
    }

    private static <T> Map<String, T> index(List<T> items, Function<T, String> key) {
        return items.stream().collect(Collectors.toMap(key, i -> i, (a, b) -> a, LinkedHashMap::new));
    }
}
