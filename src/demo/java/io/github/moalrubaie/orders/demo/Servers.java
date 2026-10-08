package io.github.moalrubaie.orders.demo;

import io.github.moalrubaie.orders.OrdersServerApplication;
import io.github.moalrubaie.orders.store.OrderStore;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts the real server application in-process, on a free port, with one profile. */
public final class Servers {

    private Servers() {}

    public record Running(String name, ConfigurableApplicationContext context, String url) implements AutoCloseable {

        /** The server's own data, to check what a task really changed. */
        public OrderStore store() {
            return context.getBean(OrderStore.class);
        }

        @Override
        public void close() {
            context.close();
        }
    }

    public static Running start(String profile) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(OrdersServerApplication.class)
                .profiles(profile)
                .logStartupInfo(false)
                .run("--server.port=0", "--logging.level.root=ERROR");
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return new Running(profile, context, "http://localhost:" + port);
    }
}
