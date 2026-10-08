package io.github.moalrubaie.orders.store;

import java.math.BigDecimal;

public record Product(
        String sku, String name, String category, BigDecimal price, int weightGrams, String description) {}
