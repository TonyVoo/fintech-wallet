package com.fintech.wallet.notification;

import com.fintech.wallet.notification.domain.Notification;
import com.fintech.wallet.notification.repository.NotificationRepository;
import com.fintech.wallet.notification.security.JwtProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real consumer against a real Kafka broker and PostgreSQL: events are produced to the topic exactly as
 * transaction-service would, and the tests check what lands in the database, the REST API and the dead-letter topic.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class NotificationApiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void kafkaAddress(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, String> producer;
    @Autowired NotificationRepository notifications;
    @Autowired MockMvc mvc;
    @Autowired JwtProperties jwt;

    @Value("${app.kafka.topic}")
    String topic;

    @Test
    void completedTransferNotifiesTheSenderAndTheReceiverAndTheyCanReadItOverRest() throws Exception {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID(), eventId = UUID.randomUUID();

        send(event(eventId, "TRANSFER_COMPLETED", sender, receiver, "300", null));

        awaitProcessed(eventId, 2);
        mvc.perform(get("/api/notifications").header("Authorization", bearer(sender)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("TRANSFER_SENT"))
                .andExpect(jsonPath("$[0].message").value("You sent 300 VND."));
        mvc.perform(get("/api/notifications").header("Authorization", bearer(receiver)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("TRANSFER_RECEIVED"))
                .andExpect(jsonPath("$[0].message").value("You received 300 VND."));
    }

    @Test
    void failedTransferNotifiesOnlyTheSender() throws Exception {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID(), eventId = UUID.randomUUID();

        send(event(eventId, "TRANSFER_FAILED", sender, receiver, "500", "INSUFFICIENT_FUNDS"));

        awaitProcessed(eventId, 1);
        assertThat(notifications.findByEventId(eventId)).singleElement()
                .satisfies(n -> assertThat(n.getUserId()).isEqualTo(sender));
        mvc.perform(get("/api/notifications").header("Authorization", bearer(receiver)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void anEventDeliveredThreeTimesStillCreatesOneNotificationPerUser() {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID();
        UUID eventId = UUID.randomUUID(), sentinelId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();

        // Kafka is at-least-once: the same message can legitimately arrive again
        String payload = event(eventId, transferId, "TRANSFER_COMPLETED", sender, receiver, "300", null);
        send(transferId, payload);
        send(transferId, payload);
        send(transferId, payload);
        // same key -> same partition -> processed in order, so once this later event is done, the three above are too
        send(transferId, event(sentinelId, transferId, "TRANSFER_COMPLETED", sender, receiver, "1", null));

        awaitProcessed(sentinelId, 2);
        assertThat(notifications.findByEventId(eventId)).hasSize(2);
    }

    @Test
    void aMalformedMessageGoesToTheDeadLetterTopicAndDoesNotBlockTheOnesBehindIt() {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID(), eventId = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        String poison = "this is not json " + UUID.randomUUID();

        send(transferId, poison);
        send(transferId, event(eventId, transferId, "TRANSFER_COMPLETED", sender, receiver, "42", null));

        awaitProcessed(eventId, 2); // the valid message behind the poison one was still handled
        assertThat(readTopic(topic + ".DLT")).contains(poison);
    }

    @Test
    void anEventTypeFromANewerProducerIsIgnoredWithoutAnError() {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID(), sentinelId = UUID.randomUUID(), transferId = UUID.randomUUID();

        send(transferId, event(unknownId, transferId, "TRANSFER_SOMETHING_NEW", sender, receiver, "5", null));
        send(transferId, event(sentinelId, transferId, "TRANSFER_COMPLETED", sender, receiver, "5", null));

        awaitProcessed(sentinelId, 2);
        assertThat(notifications.findByEventId(unknownId)).isEmpty();
    }

    @Test
    void notificationsAreOnlyVisibleToTheirOwnerAndNeedAToken() throws Exception {
        UUID sender = UUID.randomUUID(), receiver = UUID.randomUUID(), stranger = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        send(event(eventId, "TRANSFER_COMPLETED", sender, receiver, "7", null));
        awaitProcessed(eventId, 2);

        mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications").header("Authorization", bearer(stranger)))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    private void awaitProcessed(UUID eventId, int expectedNotifications) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<Notification> found = notifications.findByEventId(eventId);
            assertThat(found).hasSize(expectedNotifications);
        });
    }

    private void send(String payload) {
        send(UUID.randomUUID(), payload);
    }

    private void send(UUID key, String payload) {
        try {
            producer.send(topic, key.toString(), payload).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String event(UUID eventId, String type, UUID from, UUID to, String amount, String reason) {
        return event(eventId, UUID.randomUUID(), type, from, to, amount, reason);
    }

    private static String event(UUID eventId, UUID transferId, String type, UUID from, UUID to, String amount,
                                String reason) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"occurredAt\":\"" + Instant.now()
                + "\",\"transferId\":\"" + transferId + "\",\"fromUserId\":\"" + from + "\",\"toUserId\":\"" + to
                + "\",\"amount\":" + amount + ",\"currency\":\"VND\",\"status\":\"X\",\"failureReason\":"
                + (reason == null ? "null" : "\"" + reason + "\"") + "}";
    }

    /** All message values currently in a topic, read from the beginning. */
    private static List<String> readTopic(String topicName) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topicName));
            long deadline = System.currentTimeMillis() + 20_000;
            int quietPolls = 0;
            while (System.currentTimeMillis() < deadline && quietPolls < 3) {
                var records = consumer.poll(Duration.ofMillis(500));
                quietPolls = records.isEmpty() && !values.isEmpty() ? quietPolls + 1 : 0;
                records.forEach(r -> values.add(r.value()));
            }
        }
        return values;
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
