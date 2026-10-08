package io.github.moalrubaie.orders.store;

import java.time.LocalDate;
import java.util.List;

public record Shipment(
        String id,
        String orderId,
        String carrier,
        String trackingNumber,
        LocalDate shippedOn,
        LocalDate expectedOn,
        LocalDate deliveredOn,
        List<Scan> scans) {

    public Scan lastScan() {
        return scans.getLast();
    }

    /** Still travelling after the date the carrier promised. */
    public boolean isLate(LocalDate today) {
        return deliveredOn == null && expectedOn.isBefore(today);
    }
}
