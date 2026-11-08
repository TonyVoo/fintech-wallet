package com.fintech.wallet.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;

/**
 * Decides WHOSE bucket a request is counted against.
 *
 * <p>Both resolvers are safe against spoofing: the user comes from a token whose signature the gateway has
 * already verified, and the IP is the real TCP peer address. X-Forwarded-For is deliberately NOT trusted, since any
 * client could invent it to dodge the limit. Behind a load balancer, configure the trusted proxy so the peer address
 * is the client's address, otherwise every client would share the load balancer's bucket.
 */
@Configuration
public class KeyResolvers {

    /**
     * For routes that are open to anonymous callers (login, register). Also the default, so a route that forgets
     * to name a resolver is still limited (per IP) instead of failing or running unlimited.
     */
    @Bean
    @Primary
    KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + clientIp(exchange));
    }

    /** For authenticated routes: one bucket per user, so one noisy user cannot slow down the others. */
    @Bean
    KeyResolver userKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName) // the JWT subject
                .map(user -> "user:" + user)
                .switchIfEmpty(Mono.fromSupplier(() -> "ip:" + clientIp(exchange)));
    }

    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }
}
