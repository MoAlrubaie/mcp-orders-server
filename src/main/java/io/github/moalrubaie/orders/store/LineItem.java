package io.github.moalrubaie.orders.store;

import java.math.BigDecimal;

public record LineItem(String sku, int quantity, BigDecimal unitPrice) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
