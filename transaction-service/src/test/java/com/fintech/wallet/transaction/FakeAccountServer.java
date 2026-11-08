package com.fintech.wallet.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for account-service's internal API, with the same rules (idempotent operation ids, no overdraft)
 * plus switches to inject the failures that are hard to cause for real: lost responses, outages, a vanished account.
 */
final class FakeAccountServer {

    private static final String PREFIX = "/internal/accounts/";

    static final class FakeAccount {
        final UUID id = UUID.randomUUID();
        final UUID ownerId;
        final String currency;
        BigDecimal balance;

        FakeAccount(UUID ownerId, String currency, BigDecimal balance) {
            this.ownerId = ownerId;
            this.currency = currency;
            this.balance = balance;
        }
    }

    private final String apiKey;
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<UUID, FakeAccount> accounts = new ConcurrentHashMap<>();
    private final Map<String, String> operations = new ConcurrentHashMap<>(); // operationId -> what it did
    private final Set<UUID> creditReportsNotFound = ConcurrentHashMap.newKeySet();
    private final AtomicInteger failNextCredits = new AtomicInteger();
    private final AtomicInteger loseNextDebitResponses = new AtomicInteger();

    private FakeAccountServer(String apiKey) throws IOException {
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(8));
        this.server.createContext(PREFIX, exchange -> {
            try {
                handle(exchange);
            } catch (Exception e) {
                respond(exchange, 500, "{}");
            } finally {
                exchange.close();
            }
        });
    }

    static FakeAccountServer start(String apiKey) {
        try {
            FakeAccountServer fake = new FakeAccountServer(apiKey);
            fake.server.start();
            return fake;
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

    // ------------------------------------------------------------------ test controls

    UUID addAccount(UUID ownerId, String currency, String balance) {
        FakeAccount account = new FakeAccount(ownerId, currency, new BigDecimal(balance));
        accounts.put(account.id, account);
        return account.id;
    }

    BigDecimal balance(UUID accountId) {
        return accounts.get(accountId).balance;
    }

    /** The next {@code count} credit calls fail with HTTP 500 before changing anything (an outage). */
    void failNextCredits(int count) {
        failNextCredits.set(count);
    }

    /** The next {@code count} debits ARE applied, but the response is lost (HTTP 500), like a timeout after commit. */
    void loseNextDebitResponses(int count) {
        loseNextDebitResponses.set(count);
    }

    /** Lookups still find the account, but crediting it answers 404, as if it was closed in between. */
    void creditsReportNotFound(UUID accountId) {
        creditReportsNotFound.add(accountId);
    }

    // ------------------------------------------------------------------ the fake API

    private void handle(HttpExchange exchange) throws IOException {
        if (!apiKey.equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"))) {
            respond(exchange, 401, "{}");
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().substring(PREFIX.length()).split("/");
        UUID id = UUID.fromString(parts[0]);

        if ("GET".equals(exchange.getRequestMethod()) && parts.length == 1) {
            FakeAccount account = accounts.get(id);
            if (account == null) {
                respond(exchange, 404, "{}");
                return;
            }
            respond(exchange, 200, mapper.writeValueAsString(
                    Map.of("id", account.id, "ownerId", account.ownerId, "currency", account.currency)));
            return;
        }
        if ("POST".equals(exchange.getRequestMethod()) && parts.length == 2) {
            apply(exchange, id, parts[1], mapper.readTree(exchange.getRequestBody()));
            return;
        }
        respond(exchange, 404, "{}");
    }

    private synchronized void apply(HttpExchange exchange, UUID id, String action, JsonNode body) throws IOException {
        boolean credit = "credit".equals(action);

        if (credit && failNextCredits.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, 500, "{}");
            return;
        }
        FakeAccount account = accounts.get(id);
        if (account == null || (credit && creditReportsNotFound.contains(id))) {
            respond(exchange, 404, "{}");
            return;
        }

        String operationId = body.get("operationId").asText();
        BigDecimal amount = new BigDecimal(body.get("amount").asText());
        String signature = id + "|" + action + "|" + amount.stripTrailingZeros().toPlainString();

        String prior = operations.get(operationId);
        boolean replay = prior != null;
        if (replay) {
            if (!prior.equals(signature)) {
                respond(exchange, 409, "{}");
                return;
            }
        } else {
            if (!credit && account.balance.compareTo(amount) < 0) {
                respond(exchange, 422, "{}");
                return;
            }
            account.balance = credit ? account.balance.add(amount) : account.balance.subtract(amount);
            operations.put(operationId, signature);

            if (!credit && loseNextDebitResponses.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                respond(exchange, 500, "{}"); // applied, but the caller never finds out
                return;
            }
        }
        respond(exchange, 200, "{\"status\":\"" + (replay ? "ALREADY_APPLIED" : "APPLIED") + "\"}");
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
