package com.fintech.wallet.transaction;

import com.fintech.wallet.transaction.domain.EntryDirection;
import com.fintech.wallet.transaction.domain.LedgerEntry;
import com.fintech.wallet.transaction.domain.Transfer;
import com.fintech.wallet.transaction.outbox.OutboxEvent;
import com.fintech.wallet.transaction.outbox.OutboxEventRepository;
import com.fintech.wallet.transaction.outbox.OutboxPublisher;
import com.fintech.wallet.transaction.outbox.OutboxWriter;
import com.fintech.wallet.transaction.repository.LedgerEntryRepository;
import com.fintech.wallet.transaction.repository.TransferRepository;
import com.fintech.wallet.transaction.security.JwtProperties;
import com.fintech.wallet.transaction.service.TransferRecovery;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole service against a real PostgreSQL and a fake account-service that can be told to misbehave.
 * The scheduled recovery job is switched off so that each test triggers recovery explicitly and deterministically.
 */
@SpringBootTest(properties = {"app.recovery.enabled=false", "app.outbox.enabled=false", "app.accounts.api-key=test-key"})
@AutoConfigureMockMvc
@Testcontainers
class TransferApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    static final FakeAccountServer accounts = FakeAccountServer.start("test-key");

    @DynamicPropertySource
    static void dependencies(DynamicPropertyRegistry registry) {
        registry.add("app.accounts.base-url", accounts::baseUrl);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @AfterAll
    static void stopFake() {
        accounts.stop();
    }

    @Autowired MockMvc mvc;
    @Autowired JwtProperties jwt;
    @Autowired TransferRecovery recovery;
    @Autowired TransferRepository transfers;
    @Autowired LedgerEntryRepository ledger;
    @Autowired OutboxEventRepository outbox;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxWriter outboxWriter;

    // ---------------------------------------------------------------- the happy path

    @Test
    void completedTransferMovesMoneyAndBooksBalancedLedgerEntries() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(bob, "VND", "0");

        ResultActions response = transfer(alice, "key-" + UUID.randomUUID(), from, to, "300");

        response.andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.currency").value("VND"));
        assertThat(accounts.balance(from)).isEqualByComparingTo("700");
        assertThat(accounts.balance(to)).isEqualByComparingTo("300");

        UUID id = UUID.fromString(JsonPath.read(body(response), "$.id"));
        List<LedgerEntry> entries = ledger.findByTransferId(id);
        assertThat(entries).hasSize(2);
        assertThat(entries).filteredOn(e -> e.getDirection() == EntryDirection.DEBIT)
                .singleElement().satisfies(e -> {
                    assertThat(e.getAccountId()).isEqualTo(from);
                    assertThat(e.getAmount()).isEqualByComparingTo("300");
                });
        assertThat(entries).filteredOn(e -> e.getDirection() == EntryDirection.CREDIT)
                .singleElement().satisfies(e -> assertThat(e.getAccountId()).isEqualTo(to));
        // double entry: what was debited equals what was credited
        assertThat(sum(entries, EntryDirection.DEBIT)).isEqualByComparingTo(sum(entries, EntryDirection.CREDIT));

        // only the initiator can read the transfer
        mvc.perform(get("/api/transfers/" + id).header("Authorization", bearer(alice))).andExpect(status().isOk());
        mvc.perform(get("/api/transfers/" + id).header("Authorization", bearer(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/transfers").header("Authorization", bearer(alice)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
    }

    @Test
    void requestsNeedAValidToken() throws Exception {
        mvc.perform(post("/api/transfers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/transfers")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- idempotency

    @Test
    void repeatingARequestWithTheSameKeyMovesMoneyOnlyOnce() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        String key = "key-" + UUID.randomUUID();

        ResultActions first = transfer(alice, key, from, to, "300").andExpect(status().isCreated());
        ResultActions retry = transfer(alice, key, from, to, "300").andExpect(status().isOk());

        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(accounts.balance(from)).isEqualByComparingTo("700");
        assertThat(accounts.balance(to)).isEqualByComparingTo("300");
    }

    @Test
    void reusingAKeyForADifferentRequestIsRefused() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        String key = "key-" + UUID.randomUUID();
        transfer(alice, key, from, to, "300").andExpect(status().isCreated());

        transfer(alice, key, from, to, "999").andExpect(status().isUnprocessableEntity());

        assertThat(accounts.balance(from)).isEqualByComparingTo("700");
    }

    @Test
    void sameKeyFromDifferentUsersAreIndependent() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        UUID a = accounts.addAccount(alice, "VND", "100"), b = accounts.addAccount(bob, "VND", "100");
        UUID sink = accounts.addAccount(UUID.randomUUID(), "VND", "0");

        transfer(alice, "shared-key", a, sink, "10").andExpect(status().isCreated());
        transfer(bob, "shared-key", b, sink, "10").andExpect(status().isCreated());

        assertThat(accounts.balance(sink)).isEqualByComparingTo("20");
    }

    @Test
    void tenIdenticalRequestsAtTheSameInstantCreateExactlyOneTransfer() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        String key = "key-" + UUID.randomUUID();

        List<String> responses = runConcurrently(10, () -> {
            var result = transfer(alice, key, from, to, "300").andReturn().getResponse();
            return result.getStatus() + ":" + JsonPath.read(result.getContentAsString(), "$.id");
        });

        // all ten refer to the same single transfer, and at most one of them is the creator
        assertThat(responses.stream().map(r -> r.substring(r.indexOf(':') + 1)).distinct()).hasSize(1);
        assertThat(responses.stream().filter(r -> r.startsWith("201"))).hasSizeLessThanOrEqualTo(1);
        assertThat(responses).allMatch(r -> r.startsWith("200") || r.startsWith("201") || r.startsWith("202"));

        recovery.recoverStuck(Duration.ZERO); // settle anything still in flight
        assertThat(accounts.balance(from)).isEqualByComparingTo("700"); // debited once, not ten times
        assertThat(accounts.balance(to)).isEqualByComparingTo("300");
    }

    // ---------------------------------------------------------------- refusals that move no money

    @Test
    void insufficientFundsFailsTheTransferWithoutMovingMoneyOrBookingEntries() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "50"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        String key = "key-" + UUID.randomUUID();

        ResultActions response = transfer(alice, key, from, to, "100")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));

        assertThat(accounts.balance(from)).isEqualByComparingTo("50");
        assertThat(accounts.balance(to)).isEqualByComparingTo("0");
        assertThat(ledger.findByTransferId(idOf(response))).isEmpty();

        // retrying the same request gives the same answer, it is not attempted again as a new transfer
        ResultActions again = transfer(alice, key, from, to, "100").andExpect(status().isUnprocessableEntity());
        assertThat(idOf(again)).isEqualTo(idOf(response));
    }

    @Test
    void cannotSpendFromSomeoneElsesAccount() throws Exception {
        UUID owner = UUID.randomUUID(), thief = UUID.randomUUID();
        UUID victim = accounts.addAccount(owner, "VND", "1000");
        UUID mine = accounts.addAccount(thief, "VND", "0");

        transfer(thief, "key-" + UUID.randomUUID(), victim, mine, "500").andExpect(status().isNotFound());

        assertThat(accounts.balance(victim)).isEqualByComparingTo("1000");
    }

    @Test
    void invalidRequestsAreRejectedBeforeAnythingHappens() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "100");
        UUID usd = accounts.addAccount(UUID.randomUUID(), "USD", "0");
        UUID ok = accounts.addAccount(UUID.randomUUID(), "VND", "0");

        transfer(alice, "k-" + UUID.randomUUID(), from, UUID.randomUUID(), "10").andExpect(status().isNotFound());
        transfer(alice, "k-" + UUID.randomUUID(), from, usd, "10").andExpect(status().isUnprocessableEntity());
        transfer(alice, "k-" + UUID.randomUUID(), from, from, "10").andExpect(status().isBadRequest());
        transfer(alice, "k-" + UUID.randomUUID(), from, ok, "0").andExpect(status().isBadRequest());
        transfer(alice, "k-" + UUID.randomUUID(), from, ok, "-5").andExpect(status().isBadRequest());

        // Idempotency-Key is mandatory and must be well-formed
        mvc.perform(post("/api/transfers").header("Authorization", bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON).content(json(from, ok, "10")))
                .andExpect(status().isBadRequest());
        transfer(alice, "bad key with spaces", from, ok, "10").andExpect(status().isBadRequest());

        assertThat(accounts.balance(from)).isEqualByComparingTo("100");
    }

    // ---------------------------------------------------------------- failures in the middle of the saga

    @Test
    void destinationVanishingAfterTheDebitRefundsTheSource() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        accounts.creditsReportNotFound(to);

        ResultActions response = transfer(alice, "key-" + UUID.randomUUID(), from, to, "300")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.failureReason").value("DESTINATION_ACCOUNT_UNAVAILABLE"));

        // compensating action: the 300 that left the source came back
        assertThat(accounts.balance(from)).isEqualByComparingTo("1000");
        assertThat(accounts.balance(to)).isEqualByComparingTo("0");
        assertThat(ledger.findByTransferId(idOf(response))).isEmpty();
    }

    @Test
    void anOutageAfterTheDebitLeavesTheTransferInFlightAndRecoveryFinishesIt() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        accounts.failNextCredits(1);

        ResultActions response = transfer(alice, "key-" + UUID.randomUUID(), from, to, "300")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DEBITED"));
        UUID id = idOf(response);
        assertThat(accounts.balance(from)).isEqualByComparingTo("700"); // money is in flight
        assertThat(accounts.balance(to)).isEqualByComparingTo("0");

        recovery.recoverStuck(Duration.ZERO);

        mvc.perform(get("/api/transfers/" + id).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(accounts.balance(from)).isEqualByComparingTo("700");
        assertThat(accounts.balance(to)).isEqualByComparingTo("300");
        assertThat(ledger.findByTransferId(id)).hasSize(2);

        // running recovery again must not pay the destination a second time
        recovery.recoverStuck(Duration.ZERO);
        assertThat(accounts.balance(to)).isEqualByComparingTo("300");
    }

    @Test
    void aLostDebitResponseIsNotTreatedAsAFailureAndTheMoneyIsDebitedOnce() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        accounts.loseNextDebitResponses(1); // the debit is applied, but we get an error back

        ResultActions response = transfer(alice, "key-" + UUID.randomUUID(), from, to, "300")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(accounts.balance(from)).isEqualByComparingTo("700"); // it did happen

        // recovery sends the debit again with the same operation id: account-service recognises it
        recovery.recoverStuck(Duration.ZERO);

        assertThat(accounts.balance(from)).isEqualByComparingTo("700"); // not 400
        assertThat(accounts.balance(to)).isEqualByComparingTo("300");
        mvc.perform(get("/api/transfers/" + idOf(response)).header("Authorization", bearer(alice)))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void parallelTransfersFromOneAccountCanNeverOverdrawIt() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "100"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");

        List<Integer> statuses = runConcurrently(5, () ->
                transfer(alice, "key-" + UUID.randomUUID(), from, to, "30").andReturn().getResponse().getStatus());

        // 100 / 30: exactly three can succeed, the other two are refused
        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(3);
        assertThat(statuses.stream().filter(s -> s == 422)).hasSize(2);
        assertThat(accounts.balance(from)).isEqualByComparingTo("10");
        assertThat(accounts.balance(to)).isEqualByComparingTo("90");
    }

    // ---------------------------------------------------------------- transactional outbox / Kafka

    @Test
    void completedTransferWritesOneEventThatReachesKafka() throws Exception {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(bob, "VND", "0");

        UUID id = idOf(transfer(alice, "key-" + UUID.randomUUID(), from, to, "300").andExpect(status().isCreated()));

        // the event was written together with the state change, before anything talked to Kafka
        List<OutboxEvent> rows = outbox.findByAggregateId(id);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(rows.get(0).getPublishedAt()).isNull();

        publisher.publishPending();

        assertThat(outbox.findByAggregateId(id).get(0).getPublishedAt()).isNotNull();
        List<ConsumerRecord<String, String>> messages = readTopic(id.toString());
        assertThat(messages).hasSize(1);
        String payload = messages.get(0).value();
        assertThat(JsonPath.<String>read(payload, "$.eventId")).isEqualTo(rows.get(0).getId().toString());
        assertThat(JsonPath.<String>read(payload, "$.type")).isEqualTo("TRANSFER_COMPLETED");
        assertThat(JsonPath.<String>read(payload, "$.fromUserId")).isEqualTo(alice.toString());
        assertThat(JsonPath.<String>read(payload, "$.toUserId")).isEqualTo(bob.toString());
        assertThat(new BigDecimal(JsonPath.read(payload, "$.amount").toString())).isEqualByComparingTo("300");
        assertThat(JsonPath.<String>read(payload, "$.currency")).isEqualTo("VND");

        // publishing again must not send it a second time
        publisher.publishPending();
        assertThat(readTopic(id.toString())).hasSize(1);
    }

    @Test
    void failedAndRefundedTransfersPublishTheirOwnEventTypes() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID poor = accounts.addAccount(alice, "VND", "10");
        UUID rich = accounts.addAccount(alice, "VND", "1000");
        UUID gone = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        accounts.creditsReportNotFound(gone);

        UUID failed = idOf(transfer(alice, "key-" + UUID.randomUUID(), poor, gone, "500")
                .andExpect(status().isUnprocessableEntity()));
        UUID refunded = idOf(transfer(alice, "key-" + UUID.randomUUID(), rich, gone, "500")
                .andExpect(status().isUnprocessableEntity()));

        OutboxEvent failedEvent = outbox.findByAggregateId(failed).get(0);
        OutboxEvent refundedEvent = outbox.findByAggregateId(refunded).get(0);
        assertThat(failedEvent.getType()).isEqualTo("TRANSFER_FAILED");
        assertThat(JsonPath.<String>read(failedEvent.getPayload(), "$.failureReason")).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(refundedEvent.getType()).isEqualTo("TRANSFER_REFUNDED");
    }

    @Test
    void anInFlightTransferHasNoEventUntilItFinishes() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        accounts.failNextCredits(1);

        UUID id = idOf(transfer(alice, "key-" + UUID.randomUUID(), from, to, "300").andExpect(status().isAccepted()));
        assertThat(outbox.findByAggregateId(id)).isEmpty(); // money in flight: nothing to announce yet

        recovery.recoverStuck(Duration.ZERO);
        recovery.recoverStuck(Duration.ZERO);

        assertThat(outbox.findByAggregateId(id)).hasSize(1); // exactly one, however often recovery ran
    }

    @Test
    void tenDuplicateRequestsProduceExactlyOneEvent() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID from = accounts.addAccount(alice, "VND", "1000"), to = accounts.addAccount(UUID.randomUUID(), "VND", "0");
        String key = "key-" + UUID.randomUUID();

        List<String> ids = runConcurrently(10, () -> JsonPath.<String>read(
                transfer(alice, key, from, to, "300").andReturn().getResponse().getContentAsString(), "$.id"));
        recovery.recoverStuck(Duration.ZERO);

        UUID id = UUID.fromString(ids.get(0));
        assertThat(outbox.findByAggregateId(id)).hasSize(1);
    }

    @Test
    void theOutboxCannotBeWrittenOutsideATransaction() {
        Transfer completed = new Transfer(UUID.randomUUID(), "k", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("1"), "VND", null);
        completed.markDebited();
        completed.markCompleted();

        assertThatThrownBy(() -> outboxWriter.record(completed)).isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---------------------------------------------------------------- helpers

    /** Reads the topic from the beginning and returns every message with the given key. */
    private static List<ConsumerRecord<String, String>> readTopic(String key) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("transfer-events"));
            long deadline = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                if (!found.isEmpty()) {
                    // one more poll, to notice a duplicate if there is one
                    consumer.poll(Duration.ofSeconds(1)).forEach(r -> {
                        if (key.equals(r.key())) {
                            found.add(r);
                        }
                    });
                    break;
                }
            }
        }
        return found;
    }

    private ResultActions transfer(UUID user, String key, UUID from, UUID to, String amount) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/transfers")
                .header("Authorization", bearer(user))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(from, to, amount));
        return mvc.perform(request);
    }

    private static String json(UUID from, UUID to, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amount\":" + amount + "}";
    }

    private static String body(ResultActions response) throws Exception {
        return response.andReturn().getResponse().getContentAsString();
    }

    private static UUID idOf(ResultActions response) throws Exception {
        return UUID.fromString(JsonPath.read(body(response), "$.id"));
    }

    private static BigDecimal sum(List<LedgerEntry> entries, EntryDirection direction) {
        return entries.stream().filter(e -> e.getDirection() == direction)
                .map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Starts all tasks at the same instant to maximise contention. */
    private static <T> List<T> runConcurrently(int count, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String bearer(UUID userId) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(jwt.issuer())
                .subject(userId.toString())
                .claim("role", "USER")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(Base64.getDecoder().decode(jwt.secret())));
        return "Bearer " + token.serialize();
    }
}
