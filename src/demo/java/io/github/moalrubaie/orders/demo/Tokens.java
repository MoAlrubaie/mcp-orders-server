package io.github.moalrubaie.orders.demo;

/**
 * A rough token count: characters divided by four, the usual rule of thumb for English and
 * JSON. Good enough to compare two servers; not what any particular model would bill.
 */
public final class Tokens {

    private Tokens() {}

    public static int of(String text) {
        return text == null ? 0 : (text.length() + 3) / 4;
    }
}
