package io.github.moalrubaie.orders.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SeedDataTest {

    private final OrderStore store = SeedData.store();

    @Test
    void theSameDataEveryRun() {
        assertThat(SeedData.store().orders()).isEqualTo(store.orders());
        assertThat(SeedData.store().customers()).isEqualTo(store.customers());
    }

    @Test
    void sizes() {
        assertThat(store.customers()).hasSize(60);
        assertThat(store.products()).hasSize(20);
        assertThat(store.orders()).hasSize(600);
        assertThat(store.ordersFor(SeedData.DANA)).hasSize(10);
    }

    @Test
    void invoice8841IsDanasOrderThatIsOnItsWay() {
        Order order = store.orderByInvoice(8841).orElseThrow();
        assertThat(order.id()).isEqualTo(SeedData.orderIdOn(LocalDate.of(2026, 9, 29)));
        assertThat(order.customerId()).isEqualTo(SeedData.DANA);
        Shipment shipment = store.shipmentsFor(order.id()).getFirst();
        assertThat(shipment.carrier()).isEqualTo("CarrierX");
        assertThat(shipment.expectedOn()).isEqualTo(LocalDate.of(2026, 10, 6));
        assertThat(shipment.lastScan().location()).isEqualTo("Pittsburgh, PA");
        assertThat(shipment.isLate(store.today())).isFalse();
    }

    @Test
    void danaHasOneLateOrder() {
        assertThat(store.ordersFor(SeedData.DANA).stream()
                        .flatMap(o -> store.shipmentsFor(o.id()).stream())
                        .filter(s -> s.isLate(store.today()))
                        .map(Shipment::orderId))
                .containsExactly(SeedData.orderIdOn(LocalDate.of(2026, 10, 1)));
    }

    @Test
    void theInjectedNoteSitsOnSomeoneElsesOrder() {
        Order order = store.order("ORD-10003").orElseThrow();
        assertThat(order.customerNote()).isEqualTo(SeedData.INJECTED_NOTE);
        assertThat(order.customerId()).isNotEqualTo(SeedData.DANA);
    }

    @Test
    void shipmentsNeverHappenInTheFuture() {
        for (Order order : store.orders()) {
            for (Shipment s : store.shipmentsFor(order.id())) {
                assertThat(s.shippedOn()).isAfterOrEqualTo(order.placedOn()).isBeforeOrEqualTo(store.today());
                assertThat(s.scans()).allSatisfy(scan -> assertThat(scan.on()).isBeforeOrEqualTo(store.today()));
                if (s.deliveredOn() != null) {
                    assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
                }
            }
        }
    }
}
