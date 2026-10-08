package io.github.moalrubaie.orders.store;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

public record Order(
        String id,
        int invoiceNumber,
        String customerId,
        LocalDate placedOn,
        OrderStatus status,
        List<LineItem> items,
        Address shippingAddress,
        String paymentMethod,
        String channel,
        String warehouse,
        String customerNote,
        LocalDate cancelledOn,
        String cancelReason) {

    private static final BigDecimal TAX_RATE = new BigDecimal("0.08");
    private static final BigDecimal FREE_SHIPPING_FROM = new BigDecimal("75");
    private static final BigDecimal SHIPPING_FEE = new BigDecimal("6.95");

    public BigDecimal subtotal() {
        return items.stream().map(LineItem::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal tax() {
        return subtotal().multiply(TAX_RATE).setScale(2, RoundingMode.HALF_UP);
    }

    public BigDecimal shippingFee() {
        return subtotal().compareTo(FREE_SHIPPING_FROM) >= 0 ? new BigDecimal("0.00") : SHIPPING_FEE;
    }

    /** What the customer paid. */
    public BigDecimal total() {
        return subtotal().add(tax()).add(shippingFee());
    }

    public int itemCount() {
        return items.stream().mapToInt(LineItem::quantity).sum();
    }

    public Order withCustomerNote(String note) {
        return new Order(
                id,
                invoiceNumber,
                customerId,
                placedOn,
                status,
                items,
                shippingAddress,
                paymentMethod,
                channel,
                warehouse,
                note,
                cancelledOn,
                cancelReason);
    }

    Order cancelled(LocalDate on, String reason) {
        return new Order(
                id,
                invoiceNumber,
                customerId,
                placedOn,
                OrderStatus.CANCELLED,
                items,
                shippingAddress,
                paymentMethod,
                channel,
                warehouse,
                customerNote,
                on,
                reason);
    }
}
