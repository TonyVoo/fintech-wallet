package com.fintech.wallet.account;

import com.fintech.wallet.account.security.InternalApiKeyFilter;
import com.fintech.wallet.account.security.JwtProperties;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full stack against a real PostgreSQL: security filter chain, JWT validation, Flyway migrations,
 * JPA, optimistic locking and the retry loop. Nothing is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AccountApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtProperties jwt;

    @Value("${app.internal.api-key}")
    String internalKey;

    // ---------------------------------------------------------------- authentication

    @Test
    void requestsWithoutAValidTokenAreRejected() throws Exception {
        UUID user = UUID.randomUUID();

        mvc.perform(get("/api/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());

        String wrongKey = token(user, "USER", jwt.issuer(), Instant.now().plusSeconds(600),
                Base64.getEncoder().encodeToString("another-secret-another-secret-0123456789".getBytes()));
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + wrongKey))
                .andExpect(status().isUnauthorized());

        String expired = token(user, "USER", jwt.issuer(), Instant.now().minusSeconds(3600), jwt.secret());
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());

        String wrongIssuer = token(user, "USER", "someone-else", Instant.now().plusSeconds(600), jwt.secret());
        mvc.perform(get("/api/accounts").header("Authorization", "Bearer " + wrongIssuer))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- ownership

    @Test
    void ownerCanUseAccountAndOthersCannotSeeIt() throws Exception {
        String alice = bearer(UUID.randomUUID(), "USER");
        String bob = bearer(UUID.randomUUID(), "USER");
        String admin = bearer(UUID.randomUUID(), "ADMIN");

        String id = createAccount(alice);
        deposit(alice, id, "500").andExpect(status().isOk());

        mvc.perform(get("/api/accounts/" + id).header("Authorization", alice)).andExpect(status().isOk());

        // another user gets 404, not 403: the existence of the account is not revealed
        mvc.perform(get("/api/accounts/" + id).header("Authorization", bob)).andExpect(status().isNotFound());
        deposit(bob, id, "1").andExpect(status().isNotFound());
        withdraw(bob, id, "1").andExpect(status().isNotFound());
        assertThat(balance(alice, id)).isEqualByComparingTo("500");

        mvc.perform(get("/api/accounts/" + id).header("Authorization", admin)).andExpect(status().isOk());

        String mine = mvc.perform(get("/api/accounts").header("Authorization", bob))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mine).isEqualTo("[]");
    }

    // ---------------------------------------------------------------- business rules

    @Test
    void invalidInputIsRejected() throws Exception {
        String user = bearer(UUID.randomUUID(), "USER");

        mvc.perform(post("/api/accounts").header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerName\":\"\",\"currency\":\"vnd\"}"))
                .andExpect(status().isBadRequest());

        String id = createAccount(user);
        deposit(user, id, "0").andExpect(status().isBadRequest());
        deposit(user, id, "-5").andExpect(status().isBadRequest());
    }

    @Test
    void withdrawingMoreThanTheBalanceIsRefusedAndChangesNothing() throws Exception {
        String user = bearer(UUID.randomUUID(), "USER");
        String id = createAccount(user);
        deposit(user, id, "100").andExpect(status().isOk());

        withdraw(user, id, "100.01").andExpect(status().isUnprocessableEntity());

        assertThat(balance(user, id)).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------- concurrency

    @Test
    void concurrentDepositsAreNeverLost() throws Exception {
        String user = bearer(UUID.randomUUID(), "USER");
        String id = createAccount(user);
        int threads = 30;

        List<Integer> statuses = runConcurrently(threads, () -> deposit(user, id, "10").andReturn().getResponse().getStatus());

        // optimistic-lock conflicts were retried, so every request succeeded and no deposit was lost
        assertThat(statuses).hasSize(threads).allMatch(s -> s == 200);
        assertThat(balance(user, id)).isEqualByComparingTo(BigDecimal.valueOf(threads * 10L));
    }

    @Test
    void concurrentWithdrawalsCanNeverOverdrawTheAccount() throws Exception {
        String user = bearer(UUID.randomUUID(), "USER");
        String id = createAccount(user);
        deposit(user, id, "100").andExpect(status().isOk());

        List<Integer> statuses = runConcurrently(10, () -> withdraw(user, id, "30").andReturn().getResponse().getStatus());

        // 100 / 30 = exactly 3 withdrawals can succeed; the other 7 must be refused
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(7);
        assertThat(balance(user, id)).isEqualByComparingTo("10");
    }

    // ---------------------------------------------------------------- service-to-service API

    @Test
    void internalEndpointsNeedTheServiceApiKey() throws Exception {
        String user = bearer(UUID.randomUUID(), "ADMIN");
        String id = createAccount(user);
        String body = "{\"operationId\":\"op-" + UUID.randomUUID() + "\",\"amount\":1}";

        mvc.perform(post("/internal/accounts/" + id + "/credit").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/accounts/" + id + "/credit").header(InternalApiKeyFilter.HEADER, "wrong-key")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // even an ADMIN user token is not a service credential
        mvc.perform(post("/internal/accounts/" + id + "/credit").header("Authorization", user)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        // and the service key is not a user credential
        mvc.perform(get("/api/accounts/" + id).header(InternalApiKeyFilter.HEADER, internalKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sameOperationIdIsAppliedOnlyOnce() throws Exception {
        String owner = bearer(UUID.randomUUID(), "USER");
        String id = createAccount(owner);
        deposit(owner, id, "100").andExpect(status().isOk());
        String op = "op-" + UUID.randomUUID();

        internal(id, "debit", op, "30").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPLIED"));
        // a retry after a timeout: same answer, no second debit
        internal(id, "debit", op, "30").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALREADY_APPLIED"));

        assertThat(balance(owner, id)).isEqualByComparingTo("70");

        // same id but a different amount can only be a caller bug: refuse it
        internal(id, "debit", op, "31").andExpect(status().isConflict());
        assertThat(balance(owner, id)).isEqualByComparingTo("70");
    }

    @Test
    void internalDebitCannotOverdrawAndUnknownAccountsAreReported() throws Exception {
        String owner = bearer(UUID.randomUUID(), "USER");
        String id = createAccount(owner);
        deposit(owner, id, "10").andExpect(status().isOk());

        internal(id, "debit", "op-" + UUID.randomUUID(), "10.01").andExpect(status().isUnprocessableEntity());
        internal(UUID.randomUUID().toString(), "credit", "op-" + UUID.randomUUID(), "5")
                .andExpect(status().isNotFound());

        assertThat(balance(owner, id)).isEqualByComparingTo("10");
    }

    @Test
    void internalCreditAndLookupWork() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String owner = bearer(ownerId, "USER");
        String id = createAccount(owner);

        internal(id, "credit", "op-" + UUID.randomUUID(), "25.5").andExpect(status().isOk());

        assertThat(balance(owner, id)).isEqualByComparingTo("25.5");
        mvc.perform(get("/internal/accounts/" + id).header(InternalApiKeyFilter.HEADER, internalKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$.currency").value("VND"));
    }

    @Test
    void manyParallelCopiesOfTheSameOperationChangeTheBalanceOnce() throws Exception {
        String owner = bearer(UUID.randomUUID(), "USER");
        String id = createAccount(owner);
        deposit(owner, id, "100").andExpect(status().isOk());
        String op = "op-" + UUID.randomUUID();

        List<Integer> statuses = runConcurrently(10, () -> internal(id, "debit", op, "10").andReturn().getResponse().getStatus());

        assertThat(statuses).hasSize(10).allMatch(s -> s == 200);
        assertThat(balance(owner, id)).isEqualByComparingTo("90");
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions internal(String accountId, String action, String operationId,
                                                                        String amount) throws Exception {
        return mvc.perform(post("/internal/accounts/" + accountId + "/" + action)
                .header(InternalApiKeyFilter.HEADER, internalKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"" + operationId + "\",\"amount\":" + amount + "}"));
    }

    private String createAccount(String bearer) throws Exception {
        MvcResult result = mvc.perform(post("/api/accounts").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Test User\",\"currency\":\"VND\"}"))
                .andExpect(status().isCreated()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private org.springframework.test.web.servlet.ResultActions deposit(String bearer, String id, String amount) throws Exception {
        return mvc.perform(amountRequest(post("/api/accounts/" + id + "/deposit"), bearer, amount));
    }

    private org.springframework.test.web.servlet.ResultActions withdraw(String bearer, String id, String amount) throws Exception {
        return mvc.perform(amountRequest(post("/api/accounts/" + id + "/withdraw"), bearer, amount));
    }

    private MockHttpServletRequestBuilder amountRequest(MockHttpServletRequestBuilder request, String bearer, String amount) {
        return request.header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private BigDecimal balance(String bearer, String id) throws Exception {
        String body = mvc.perform(get("/api/accounts/" + id).header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new BigDecimal(JsonPath.read(body, "$.balance").toString());
    }

    /** Starts all tasks at the same instant to maximise contention on the same row. */
    private static List<Integer> runConcurrently(int count, Callable<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String bearer(UUID userId, String role) throws Exception {
        return "Bearer " + token(userId, role, jwt.issuer(), Instant.now().plusSeconds(600), jwt.secret());
    }

    private static String token(UUID userId, String role, String issuer, Instant expires, String base64Secret)
            throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("role", role)
                .issueTime(new Date())
                .expirationTime(Date.from(expires))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(Base64.getDecoder().decode(base64Secret)));
        return jwt.serialize();
    }
}
