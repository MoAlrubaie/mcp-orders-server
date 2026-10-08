package io.github.moalrubaie.orders.demo;

import io.github.moalrubaie.orders.store.OrderStatus;
import io.github.moalrubaie.orders.store.OrderStore;
import io.github.moalrubaie.orders.store.SeedData;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Five questions a customer might ask, and for each server the shortest correct sequence of
 * tool calls that answers it. These are best cases: a real model would only make more calls.
 *
 * <p>The naive sequences get one thing the designed ones don't need: the host tells the agent
 * the signed-in customer's ID ({@value SeedData#DANA}), because the naive server has no idea who
 * is asking.
 */
public final class Tasks {

    /**
     * {@code expected} holds patterns the answer must contain; {@code outcome} checks the
     * server's data afterwards, for tasks that change something.
     */
    public record Task(
            String question,
            List<String> expected,
            Predicate<OrderStore> outcome,
            Function<Session, String> naive,
            Function<Session, String> designed) {

        Task(
                String question,
                List<String> expected,
                Function<Session, String> naive,
                Function<Session, String> designed) {
            this(question, expected, store -> true, naive, designed);
        }

        public boolean answered(String answer, OrderStore store) {
            return answer != null
                    && expected.stream()
                            .allMatch(p -> Pattern.compile(p).matcher(answer).find())
                    && outcome.test(store);
        }
    }

    static final String DELIVERED = SeedData.orderIdOn(LocalDate.of(2026, 9, 21));
    static final String IN_TRANSIT = SeedData.orderIdOn(LocalDate.of(2026, 9, 29)); // invoice 8841
    static final String LATE = SeedData.orderIdOn(LocalDate.of(2026, 10, 1));
    static final String NOT_SHIPPED = SeedData.orderIdOn(LocalDate.of(2026, 10, 4));
    static final List<String> SEPTEMBER = List.of(
            SeedData.orderIdOn(LocalDate.of(2026, 9, 2)),
            SeedData.orderIdOn(LocalDate.of(2026, 9, 9)),
            SeedData.orderIdOn(LocalDate.of(2026, 9, 14)),
            DELIVERED,
            IN_TRANSIT);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);

    private Tasks() {}

    public static List<Task> all() {
        return List.of(
                new Task(
                        "Has " + DELIVERED + " arrived?",
                        List.of("(?i)deliver", "(?i)sep(tember)?\\.? 25|2026-09-25|9/25"),
                        s -> {
                            JsonNode order = s.json("getOrdersById", Map.of("id", DELIVERED));
                            String shipmentId = order.get("shipmentIds").get(0).asString();
                            JsonNode shipment = s.json("getShipmentsById", Map.of("id", shipmentId));
                            return "Delivered "
                                    + day(shipment.get("deliveredAt").asString());
                        },
                        s -> s.json("get_order_status", Map.of("order_id", DELIVERED))
                                .get("summary")
                                .asString()),
                new Task(
                        "Where is order 8841?",
                        List.of("CarrierX", "(?i)oct(ober)?\\.? 6\\b|2026-10-06|10/6\\b|today", "Pittsburgh"),
                        s -> {
                            JsonNode invoice = s.json("getInvoicesById", Map.of("id", "8841"));
                            JsonNode shipment = s.json(
                                            "getOrdersByIdShipments",
                                            Map.of("id", invoice.get("orderId").asString()))
                                    .get(0);
                            JsonNode events = s.json(
                                            "getShipmentsByIdTracking",
                                            Map.of("id", shipment.get("id").asString()))
                                    .get("events");
                            return "Shipped with %s, expected %s, last scan in %s"
                                    .formatted(
                                            shipment.get("carrier").asString(),
                                            day(shipment.get("estimatedDeliveryDate")
                                                    .asString()),
                                            events.get(events.size() - 1)
                                                    .get("location")
                                                    .asString());
                        },
                        s -> {
                            String orderId = s.json("search_orders", Map.of("invoice_number", 8841))
                                    .get("orders")
                                    .get(0)
                                    .get("order_id")
                                    .asString();
                            return s.json("get_order_status", Map.of("order_id", orderId))
                                    .get("summary")
                                    .asString();
                        }),
                new Task(
                        "Are any of my orders running late?",
                        List.of(LATE),
                        s -> {
                            List<String> late = new ArrayList<>();
                            JsonNode shipped = s.json(
                                            "getOrders", Map.of("customerId", SeedData.DANA, "status", "SHIPPED"))
                                    .get("content");
                            for (JsonNode order : shipped) {
                                for (JsonNode shipment : s.json(
                                        "getOrdersByIdShipments",
                                        Map.of("id", order.get("id").asString()))) {
                                    LocalDate expected = LocalDate.parse(shipment.get("estimatedDeliveryDate")
                                            .asString());
                                    if (shipment.get("status").asString().equals("IN_TRANSIT")
                                            && expected.isBefore(SeedData.TODAY)) {
                                        late.add(order.get("id").asString());
                                    }
                                }
                            }
                            return "Late: " + String.join(", ", late);
                        },
                        s -> "Late: " + ids(s.json("search_orders", Map.of("status", "DELAYED")), "order_id")),
                new Task(
                        "What did I order in September?",
                        SEPTEMBER,
                        s -> {
                            List<String> ids = new ArrayList<>();
                            for (JsonNode order : s.json("getOrders", Map.of("customerId", SeedData.DANA))
                                    .get("content")) {
                                if (order.get("placedAt").asString().startsWith("2026-09")) {
                                    ids.add(order.get("id").asString());
                                }
                            }
                            return String.join(", ", ids);
                        },
                        s -> ids(
                                s.json(
                                        "search_orders",
                                        Map.of("placed_after", "2026-09-01", "placed_before", "2026-09-30")),
                                "order_id")),
                new Task(
                        "Cancel " + NOT_SHIPPED + ": wrong size, and I'm sure.",
                        List.of("(?i)cancell?ed"),
                        store -> store.order(NOT_SHIPPED).orElseThrow().status() == OrderStatus.CANCELLED,
                        s -> {
                            JsonNode order = s.json("postOrdersByIdCancel", Map.of("id", NOT_SHIPPED));
                            return order.get("status").asString().equals("CANCELLED")
                                    ? "Cancelled " + NOT_SHIPPED
                                    : "Not cancelled";
                        },
                        s -> {
                            // First call previews, and the agent checks with the customer. Second call does it.
                            s.json("cancel_order", Map.of("order_id", NOT_SHIPPED, "reason", "WRONG_ITEM"));
                            return s.json(
                                            "cancel_order",
                                            Map.of("order_id", NOT_SHIPPED, "reason", "WRONG_ITEM", "confirm", true))
                                    .get("message")
                                    .asString();
                        }));
    }

    private static String ids(JsonNode searchResult, String field) {
        List<String> ids = new ArrayList<>();
        searchResult.get("orders").forEach(o -> ids.add(o.get(field).asString()));
        return String.join(", ", ids);
    }

    private static String day(String isoDateOrTimestamp) {
        return DAY.format(LocalDate.parse(isoDateOrTimestamp.substring(0, 10)));
    }
}
