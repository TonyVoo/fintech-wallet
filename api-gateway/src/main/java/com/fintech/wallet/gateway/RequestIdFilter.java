package com.fintech.wallet.gateway;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an X-Request-Id, passes it to the services and returns it to the client, so one request can be
 * followed across services and quoted in a bug report. Runs before security, so even a 401 or 429 carries one.
 *
 * <p>A client-supplied id is kept only if it is a plain token; anything else (spaces, line breaks, very long) is
 * replaced, so a client cannot inject odd characters into the logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements WebFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();

        exchange.getResponse().getHeaders().set(HEADER, id);
        ServerWebExchange withId = exchange.mutate()
                .request(exchange.getRequest().mutate().headers(h -> h.set(HEADER, id)).build())
                .build();
        return chain.filter(withId);
    }
}
