package io.github.moalrubaie.orders.naive;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.moalrubaie.orders.store.Address;
import io.github.moalrubaie.orders.store.Customer;
import io.github.moalrubaie.orders.store.Order;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.Product;
import io.github.moalrubaie.orders.store.Shipment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The order service's REST representations, exactly as its public API returns them: every field,
 * nested objects expanded, hypermedia links included. Nothing here is wrong for a REST API. It's
 * just a lot to put in front of a model.
 */
final class Resources {

    private static final String API = "https://api.example.com/v2";

    private Resources() {}

    record Link(String href) {}

    record PageResource<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages,
            @JsonProperty("_links") Map<String, Link> links) {}

    record CustomerResource(
            String id,
            String name,
            String email,
            String phone,
            Address address,
            LocalDate joinedOn,
            String loyaltyTier,
            boolean marketingOptIn,
            @JsonProperty("_links") Map<String, Link> links) {}

    record ProductResource(
            String sku,
            String name,
            String category,
            BigDecimal price,
            String currency,
            int weightGrams,
            String description,
            @JsonProperty("_links") Map<String, Link> links) {}

    record ItemResource(
            String lineId,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            ProductResource product) {}

    record PaymentResource(String type, String brand, String last4, Address billingAddress) {}

    record Cancellation(String cancelledAt, String reason) {}

    record Metadata(String createdBy, String source, int version, String etag) {}

    record OrderResource(
            String id,
            int invoiceNumber,
            String status,
            String placedAt,
            String updatedAt,
            CustomerResource customer,
            List<ItemResource> items,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal shippingFee,
            BigDecimal total,
            String currency,
            Address shippingAddress,
            Address billingAddress,
            PaymentResource paymentMethod,
            String channel,
            String fulfillmentWarehouse,
            String customerNote,
            List<String> shipmentIds,
            Cancellation cancellation,
            Metadata metadata,
            @JsonProperty("_links") Map<String, Link> links) {}

    record ShipmentResource(
            String id,
            String orderId,
            String carrier,
            String trackingNumber,
            String status,
            String shippedAt,
            LocalDate estimatedDeliveryDate,
            String deliveredAt,
            @JsonProperty("_links") Map<String, Link> links) {}

    record TrackingEvent(String timestamp, String location, String description, String code) {}

    record TrackingResource(
            String shipmentId,
            String carrier,
            String trackingNumber,
            List<TrackingEvent> events,
            @JsonProperty("_links") Map<String, Link> links) {}

    record InvoiceResource(
            int id,
            String orderId,
            String customerId,
            String issuedAt,
            List<ItemResource> lines,
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal total,
            String currency,
            String paymentStatus,
            Address billingAddress,
            @JsonProperty("_links") Map<String, Link> links) {}

    static CustomerResource customer(Customer c) {
        return new CustomerResource(
                c.id(),
                c.name(),
                c.email(),
                c.phone(),
                c.address(),
                c.joinedOn(),
                c.loyaltyTier(),
                c.marketingOptIn(),
                links("self", "/customers/" + c.id(), "orders", "/customers/" + c.id() + "/orders"));
    }

    static ProductResource product(Product p) {
        return new ProductResource(
                p.sku(),
                p.name(),
                p.category(),
                p.price(),
                "USD",
                p.weightGrams(),
                p.description(),
                links("self", "/products/" + p.sku(), "inventory", "/products/" + p.sku() + "/inventory"));
    }

    static List<ItemResource> items(Order o, OrderStore store) {
        return o.items().stream()
                .map(i -> new ItemResource(
                        o.id() + "-" + i.sku(),
                        i.sku(),
                        i.quantity(),
                        i.unitPrice(),
                        i.lineTotal(),
                        product(store.product(i.sku()).orElseThrow())))
                .toList();
    }

    static OrderResource order(Order o, OrderStore store) {
        Customer c = store.customer(o.customerId()).orElseThrow();
        String[] payment = o.paymentMethod().split(" ending ");
        List<String> shipmentIds =
                store.shipmentsFor(o.id()).stream().map(Shipment::id).toList();
        return new OrderResource(
                o.id(),
                o.invoiceNumber(),
                o.status().name(),
                timestamp(o.placedOn(), o.id()),
                timestamp(o.cancelledOn() != null ? o.cancelledOn() : o.placedOn(), o.id()),
                customer(c),
                items(o, store),
                o.subtotal(),
                o.tax(),
                o.shippingFee(),
                o.total(),
                "USD",
                o.shippingAddress(),
                c.address(),
                new PaymentResource("card", payment[0], payment[1], c.address()),
                o.channel(),
                o.warehouse(),
                o.customerNote(),
                shipmentIds,
                o.cancelledOn() == null ? null : new Cancellation(timestamp(o.cancelledOn(), o.id()), o.cancelReason()),
                new Metadata("checkout-service", o.channel(), o.cancelledOn() == null ? 1 : 2, etag(o)),
                links(
                        "self", "/orders/" + o.id(),
                        "customer", "/customers/" + c.id(),
                        "items", "/orders/" + o.id() + "/items",
                        "shipments", "/orders/" + o.id() + "/shipments",
                        "invoice", "/invoices/" + o.invoiceNumber(),
                        "cancel", "/orders/" + o.id() + "/cancel"));
    }

    static ShipmentResource shipment(Shipment s) {
        return new ShipmentResource(
                s.id(),
                s.orderId(),
                s.carrier(),
                s.trackingNumber(),
                s.deliveredOn() != null ? "DELIVERED" : "IN_TRANSIT",
                timestamp(s.shippedOn(), s.id()),
                s.expectedOn(),
                s.deliveredOn() == null ? null : timestamp(s.deliveredOn(), s.id()),
                links(
                        "self", "/shipments/" + s.id(),
                        "order", "/orders/" + s.orderId(),
                        "tracking", "/shipments/" + s.id() + "/tracking"));
    }

    static TrackingResource tracking(Shipment s) {
        return new TrackingResource(
                s.id(),
                s.carrier(),
                s.trackingNumber(),
                s.scans().stream()
                        .map(scan -> new TrackingEvent(
                                timestamp(scan.on(), scan.location()),
                                scan.location(),
                                scan.description(),
                                scan.description().toUpperCase().replaceAll("[^A-Z]+", "_")))
                        .toList(),
                links("self", "/shipments/" + s.id() + "/tracking", "shipment", "/shipments/" + s.id()));
    }

    static InvoiceResource invoice(Order o, OrderStore store) {
        Customer c = store.customer(o.customerId()).orElseThrow();
        return new InvoiceResource(
                o.invoiceNumber(),
                o.id(),
                c.id(),
                timestamp(o.placedOn(), o.id()),
                items(o, store),
                o.subtotal(),
                o.tax(),
                o.total(),
                "USD",
                o.cancelledOn() == null ? "PAID" : "REFUNDED",
                c.address(),
                links("self", "/invoices/" + o.invoiceNumber(), "order", "/orders/" + o.id()));
    }

    static <T> PageResource<T> page(List<T> all, int page, int size, String path) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = Math.min(from + size, all.size());
        int pages = (all.size() + size - 1) / size;
        Map<String, Link> links = new LinkedHashMap<>();
        links.put("self", new Link(API + path + "?page=" + page + "&size=" + size));
        if (page + 1 < pages) {
            links.put("next", new Link(API + path + "?page=" + (page + 1) + "&size=" + size));
        }
        return new PageResource<>(all.subList(from, to), page, size, all.size(), pages, links);
    }

    // A stable, realistic-looking time of day, so output doesn't change between runs.
    private static String timestamp(LocalDate day, String seed) {
        int minutes = Math.floorMod(seed.hashCode(), 14 * 60) + 7 * 60;
        return "%sT%02d:%02d:%02dZ".formatted(day, minutes / 60, minutes % 60, Math.floorMod(seed.hashCode(), 60));
    }

    private static String etag(Order o) {
        return "W/\"" + Integer.toHexString((o.id() + o.status()).hashCode()) + "\"";
    }

    private static Map<String, Link> links(String... pairs) {
        Map<String, Link> links = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            links.put(pairs[i], new Link(API + pairs[i + 1]));
        }
        return links;
    }
}
