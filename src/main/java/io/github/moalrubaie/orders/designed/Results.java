package io.github.moalrubaie.orders.designed;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.time.LocalDate;
import java.util.List;

/**
 * What the designed tools return: only the fields the task needs, named the way the tool
 * descriptions talk about them. Each type also becomes the tool's output schema, where a field
 * is required only if {@code @JsonProperty(required = true)} says so.
 */
public final class Results {

    private Results() {}

    /** Where an order is, as the customer would ask about it. */
    public enum OrderState {
        PLACED,
        PACKED,
        SHIPPED,
        DELAYED,
        DELIVERED,
        CANCELLED
    }

    /** Why the customer is cancelling. */
    public enum CancelReason {
        CHANGED_MIND,
        WRONG_ITEM,
        FOUND_CHEAPER,
        TOO_SLOW,
        OTHER
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LastScan(
            @JsonProperty(required = true) LocalDate on,
            @JsonProperty(required = true) String location,
            @JsonProperty(required = true) String description) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OrderStatusResult(
            @JsonProperty(value = "order_id", required = true)
            String orderId,

            @JsonProperty(value = "invoice_number", required = true)
            int invoiceNumber,

            @JsonProperty(required = true) OrderState status,

            @JsonProperty(required = true)
            @JsonPropertyDescription(
                    "One sentence that answers \"where is my order?\". Safe to repeat to the customer.")
            String summary,

            @JsonProperty(required = true) List<String> items,

            @JsonProperty(value = "placed_on", required = true)
            LocalDate placedOn,

            @JsonProperty("carrier") String carrier,
            @JsonProperty("tracking_number") String trackingNumber,
            @JsonProperty("shipped_on") LocalDate shippedOn,
            @JsonProperty("expected_delivery") LocalDate expectedDelivery,
            @JsonProperty("delivered_on") LocalDate deliveredOn,
            @JsonProperty("last_scan") LastScan lastScan,

            @JsonProperty(value = "can_cancel", required = true)
            @JsonPropertyDescription("True while the order hasn't shipped; cancel it with cancel_order.")
            boolean canCancel) {}

    public record OrderSummary(
            @JsonProperty(value = "order_id", required = true)
            String orderId,

            @JsonProperty(value = "invoice_number", required = true)
            int invoiceNumber,

            @JsonProperty(value = "placed_on", required = true)
            LocalDate placedOn,

            @JsonProperty(required = true) OrderState status,
            @JsonProperty(required = true) String items,
            @JsonProperty(required = true) String total) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResult(
            @JsonProperty(required = true) List<OrderSummary> orders,

            @JsonProperty(value = "total_matches", required = true)
            int totalMatches,

            @JsonProperty("next_cursor")
            @JsonPropertyDescription(
                    "Present when there are more matches. Pass it as cursor to search_orders to get the next page.")
            String nextCursor) {}

    public record CancelResult(
            @JsonProperty(value = "order_id", required = true)
            String orderId,

            @JsonProperty(required = true)
            @JsonPropertyDescription("False means nothing has changed yet; read message for what to do next.")
            boolean cancelled,

            @JsonProperty(required = true) OrderState status,
            @JsonProperty(required = true) String message) {}
}
