package com.fintech.wallet.gateway;

import com.fintech.wallet.gateway.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway in front of four stub services, with a real Redis behind the rate limiter. The limits are lowered
 * through properties so the tests can reach them with a handful of requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.rate-limit.auth.replenish-rate=1",
        "app.rate-limit.auth.burst-capacity=3",
        "app.rate-limit.auth.requested-tokens=1",
        "app.rate-limit.transfers.replenish-rate=1",
        "app.rate-limit.transfers.burst-capacity=2",
        "app.rate-limit.general.replenish-rate=100",
        "app.rate-limit.general.burst-capacity=100"
})
@Testcontainers
class ApiGatewayIT {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static final StubService auth = StubService.start("auth");
    static final StubService accounts = StubService.start("accounts");
    static final StubService transfers = StubService.start("transfers");
    static final StubService notifications = StubService.start("notifications");

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.services.auth-url", auth::baseUrl);
        registry.add("app.services.account-url", accounts::baseUrl);
        registry.add("app.services.transaction-url", transfers::baseUrl);
        registry.add("app.services.notification-url", notifications::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        auth.stop();
        accounts.stop();
        transfers.stop();
        notifications.stop();
    }

    @Autowired WebTestClient client;
    @Autowired JwtProperties jwt;
    @Autowired ReactiveRedisConnectionFactory redisFactory;

    /** Every test starts with empty buckets, so tests cannot use up each other's allowance. */
    @BeforeEach
    void emptyBuckets() {
        redisFactory.getReactiveConnection().serverCommands().flushAll().block();
    }

    // ---------------------------------------------------------------- routing

    @Test
    void eachPathPrefixGoesToItsOwnService() throws Exception {
        String token = bearer(UUID.randomUUID(), "USER");

        get("/api/accounts/123", token).expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("accounts");
        get("/api/transfers", token).expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("transfers");
        get("/api/notifications", token).expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("notifications");
        get("/api/auth/me", token).expectStatus().isOk().expectBody().jsonPath("$.service").isEqualTo("auth");

        assertThat(accounts.last().path()).isEqualTo("/api/accounts/123"); // path is passed through unchanged
    }

    @Test
    void loginAndRegisterNeedNoToken() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        client.post().uri("/api/auth/register").exchange().expectStatus().isOk();
    }

    // ---------------------------------------------------------------- authentication at the edge

    @Test
    void missingOrInvalidTokensAreRejectedWithoutTouchingTheService() throws Exception {
        int before = accounts.hits();
        UUID user = UUID.randomUUID();

        client.get().uri("/api/accounts").exchange().expectStatus().isUnauthorized();
        get("/api/accounts", "Bearer abc.def.ghi").expectStatus().isUnauthorized();
        get("/api/accounts", token(user, "USER", jwt.issuer(), Instant.now().minusSeconds(3600), jwt.secret(), true))
                .expectStatus().isUnauthorized();                                           // expired
        get("/api/accounts", token(user, "USER", "someone-else", Instant.now().plusSeconds(600), jwt.secret(), true))
                .expectStatus().isUnauthorized();                                           // wrong issuer
        get("/api/accounts", token(user, "USER", jwt.issuer(), Instant.now().plusSeconds(600),
                Base64.getEncoder().encodeToString("another-secret-another-secret-0123456789".getBytes()), true))
                .expectStatus().isUnauthorized();                                           // signed with another key

        assertThat(accounts.hits()).isEqualTo(before); // none of them cost a downstream call
    }

    @Test
    void theValidTokenIsForwardedUnchangedForTheServiceToCheckAgain() throws Exception {
        String token = bearer(UUID.randomUUID(), "USER");

        get("/api/accounts", token).expectStatus().isOk();

        assertThat(accounts.last().authorization()).isEqualTo(token);
    }

    // ---------------------------------------------------------------- what must stay closed

    @Test
    void theInternalApiAndActuatorAreNeverReachableFromOutside() throws Exception {
        String user = bearer(UUID.randomUUID(), "USER");
        String admin = bearer(UUID.randomUUID(), "ADMIN");
        int before = accounts.hits() + auth.hits() + transfers.hits() + notifications.hits();

        // 401/403 = denied by the security rules, 404 = no route, 400 = the firewall refused a path-traversal attempt.
        // What matters is the last assertion: not a single request got through to a service.
        for (String path : new String[]{"/internal/accounts/123", "/internal/accounts/123/debit", "/actuator/env",
                "/api/accounts/../../internal/accounts/123", "/unknown"}) {
            client.get().uri(path).exchange().expectStatus().value(s -> assertThat(s).isIn(400, 401, 403, 404));
            get(path, user).expectStatus().value(s -> assertThat(s).isIn(400, 401, 403, 404));
            get(path, admin).expectStatus().value(s -> assertThat(s).isIn(400, 401, 403, 404));
        }

        assertThat(accounts.hits() + auth.hits() + transfers.hits() + notifications.hits()).isEqualTo(before);
    }

    @Test
    void aClientCannotSendTheServiceToServiceKey() throws Exception {
        client.get().uri("/api/accounts")
                .header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID(), "USER"))
                .header("X-Internal-Api-Key", "dev-internal-key-change-me")
                .exchange().expectStatus().isOk();

        assertThat(accounts.last().internalKey()).isNull();
    }

    // ---------------------------------------------------------------- request id

    @Test
    void everyResponseCarriesARequestIdAndTheServiceSeesTheSameOne() throws Exception {
        String token = bearer(UUID.randomUUID(), "USER");

        String generated = get("/api/accounts", token).expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");
        assertThat(generated).isNotBlank();
        assertThat(accounts.last().requestId()).isEqualTo(generated);

        // a sensible client-supplied id is kept, so a request can be traced from the browser through every service
        client.get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, token).header("X-Request-Id", "trace-12345678")
                .exchange().expectHeader().valueEquals("X-Request-Id", "trace-12345678");
        assertThat(accounts.last().requestId()).isEqualTo("trace-12345678");

        // an odd one is replaced instead of being copied into the logs
        String replaced = client.get().uri("/api/accounts").header(HttpHeaders.AUTHORIZATION, token)
                .header("X-Request-Id", "bad id with spaces").exchange()
                .returnResult(String.class).getResponseHeaders().getFirst("X-Request-Id");
        assertThat(replaced).isNotEqualTo("bad id with spaces").hasSize(36);

        // even a rejected request gets one
        client.get().uri("/api/accounts").exchange().expectStatus().isUnauthorized()
                .expectHeader().exists("X-Request-Id");
    }

    // ---------------------------------------------------------------- rate limiting

    @Test
    void loginIsLimitedPerIpAndRecoversAsTheBucketRefills() throws Exception {
        int before = auth.hits();

        // burst of 3 allowed
        for (int i = 0; i < 3; i++) {
            client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        }
        // the 4th is refused at the gateway and never reaches auth-service
        client.post().uri("/api/auth/login").exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(auth.hits() - before).isEqualTo(3);

        // the bucket refills at 1 token per second
        Thread.sleep(1_300);
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
    }

    @Test
    void rateLimitHeadersTellTheClientWhatIsLeft() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-RateLimit-Remaining", "2")
                .expectHeader().valueEquals("X-RateLimit-Burst-Capacity", "3");
    }

    @Test
    void transfersAreLimitedPerUserSoOneUserCannotUseUpAnothersAllowance() throws Exception {
        String busy = bearer(UUID.randomUUID(), "USER");
        String other = bearer(UUID.randomUUID(), "USER");

        get("/api/transfers", busy).expectStatus().isOk();
        get("/api/transfers", busy).expectStatus().isOk();
        get("/api/transfers", busy).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        get("/api/transfers", other).expectStatus().isOk(); // untouched by the first user's traffic
    }

    @Test
    void theLimitFollowsTheUserNotTheirIp() throws Exception {
        // both "users" connect from 127.0.0.1; if the IP was the key, the second would be blocked too
        UUID alice = UUID.randomUUID();
        String first = bearer(alice, "USER");
        String second = bearer(alice, "USER"); // another token, same user

        get("/api/transfers", first).expectStatus().isOk();
        get("/api/transfers", second).expectStatus().isOk();
        get("/api/transfers", first).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void aTightLimitOnOneRouteDoesNotAffectOthers() throws Exception {
        String token = bearer(UUID.randomUUID(), "USER");
        for (int i = 0; i < 5; i++) {
            get("/api/transfers", token);
        }

        get("/api/accounts", token).expectStatus().isOk(); // different route, different bucket
    }

    // ---------------------------------------------------------------- browser frontends

    @Test
    void corsPreflightFromTheFrontendDevServerIsAnswered() {
        client.method(HttpMethod.OPTIONS).uri("/api/transfers")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    @Test
    void anUnknownOriginDoesNotGetCorsApproval() {
        client.method(HttpMethod.OPTIONS).uri("/api/transfers")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange().expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    // ---------------------------------------------------------------- helpers

    private WebTestClient.ResponseSpec get(String path, String authorization) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, authorization).exchange();
    }

    private String bearer(UUID userId, String role) throws Exception {
        return token(userId, role, jwt.issuer(), Instant.now().plusSeconds(600), jwt.secret(), true);
    }

    private static String token(UUID userId, String role, String issuer, Instant expires, String base64Secret,
                                boolean withPrefix) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("role", role)
                .issueTime(new Date())
                .expirationTime(Date.from(expires))
                .jwtID(UUID.randomUUID().toString()) // makes two tokens of the same user differ
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(base64Secret)));
        return (withPrefix ? "Bearer " : "") + jwt.serialize();
    }
}
