package io.github.moalrubaie.orders.store;

import java.time.LocalDate;

/** One carrier tracking event. */
public record Scan(LocalDate on, String location, String description) {}
