package com.fintech.wallet.transaction.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherTest {

    private static final String TOPIC = "transfer-events";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafka,
            mock(PlatformTransactionManager.class), new OutboxProperties(true, TOPIC, 10, Duration.ofSeconds(1)));

    private OutboxEvent event(String payload) {
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "TRANSFER_COMPLETED", payload, Instant.now());
    }

    private void brokerAccepts(OutboxEvent e) {
        when(kafka.send(eq(TOPIC), eq(e.getAggregateId().toString()), eq(e.getPayload())))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private void brokerRefuses(OutboxEvent e) {
        when(kafka.send(eq(TOPIC), eq(e.getAggregateId().toString()), eq(e.getPayload())))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    @Test
    void eventsAreMarkedPublishedOnlyAfterTheBrokerAcknowledged() {
        OutboxEvent first = event("{\"n\":1}"), second = event("{\"n\":2}");
        when(repository.lockUnpublished(any())).thenReturn(List.of(first, second));
        brokerAccepts(first);
        brokerAccepts(second);

        int sent = publisher.publishPending();

        assertThat(sent).isEqualTo(2);
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void eventStaysUnpublishedWhenTheBrokerIsDown() {
        OutboxEvent e = event("{}");
        when(repository.lockUnpublished(any())).thenReturn(List.of(e));
        brokerRefuses(e);

        int sent = publisher.publishPending();

        // nothing is lost: it is still in the outbox and is retried on the next run
        assertThat(sent).isZero();
        assertThat(e.getPublishedAt()).isNull();
    }

    @Test
    void stopsAtTheFirstFailureSoEventsKeepTheirOrder() {
        OutboxEvent ok = event("{\"n\":1}"), broken = event("{\"n\":2}"), later = event("{\"n\":3}");
        when(repository.lockUnpublished(any())).thenReturn(List.of(ok, broken, later));
        brokerAccepts(ok);
        brokerRefuses(broken);

        int sent = publisher.publishPending();

        assertThat(sent).isEqualTo(1);
        assertThat(ok.getPublishedAt()).isNotNull();
        assertThat(broken.getPublishedAt()).isNull();
        assertThat(later.getPublishedAt()).isNull();
        verify(kafka, never()).send(eq(TOPIC), eq(later.getAggregateId().toString()), anyString());
        verify(kafka, times(2)).send(eq(TOPIC), anyString(), anyString());
    }

    @Test
    void nothingToDoWhenTheOutboxIsEmpty() {
        when(repository.lockUnpublished(any())).thenReturn(List.of());

        assertThat(publisher.publishPending()).isZero();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
