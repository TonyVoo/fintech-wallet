package com.fintech.wallet.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** A tiny HTTP server that stands in for one downstream service and remembers what it received. */
final class StubService {

    record Seen(String method, String path, String authorization, String requestId, String internalKey) {
    }

    private final String name;
    private final HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<Seen> last = new AtomicReference<>();

    private StubService(String name) throws IOException {
        this.name = name;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.createContext("/", this::handle);
    }

    static StubService start(String name) {
        try {
            StubService stub = new StubService(name);
            stub.server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void stop() {
        server.stop(0);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    int hits() {
        return hits.get();
    }

    Seen last() {
        return last.get();
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        last.set(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Request-Id"),
                exchange.getRequestHeaders().getFirst("X-Internal-Api-Key")));
        byte[] body = ("{\"service\":\"" + name + "\",\"path\":\"" + exchange.getRequestURI().getPath() + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
