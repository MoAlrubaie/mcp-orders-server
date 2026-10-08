package io.github.moalrubaie.orders.store;

/** What the order system stores. "Delayed" is not stored: it's a shipment past its expected date. */
public enum OrderStatus {
    PLACED,
    PACKED,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
