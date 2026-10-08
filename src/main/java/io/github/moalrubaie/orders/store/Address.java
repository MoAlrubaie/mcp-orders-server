package io.github.moalrubaie.orders.store;

public record Address(String line1, String city, String region, String postalCode, String country) {

    public String cityAndRegion() {
        return city + ", " + region;
    }
}
