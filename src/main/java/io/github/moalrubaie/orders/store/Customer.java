package io.github.moalrubaie.orders.store;

import java.time.LocalDate;

public record Customer(
        String id,
        String name,
        String email,
        String phone,
        Address address,
        LocalDate joinedOn,
        String loyaltyTier,
        boolean marketingOptIn) {}
