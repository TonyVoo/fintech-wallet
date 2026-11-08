package com.fintech.wallet.transaction.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Forwards outbox rows to Kafka.
 *
 * <p>Delivery is AT-LEAST-ONCE: an event is marked published only after Kafka acknowledged it. If the service dies
 * between the acknowledgement and the commit, the event is sent again after restart, so consumers must ignore
 * duplicates (they do, by eventId). Events are never lost: while Kafka is down they simply wait in the table and
 * transfers keep working.
 *
 * <p>This is the one place where a database transaction stays open during a network call. It is deliberate and
 * contained: the transaction only holds locks on the outbox rows of the current small batch, the send has a
 * timeout, and nothing on the transfer request path waits for it.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository events;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxEventRepository events, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager transactionManager, OutboxProperties properties) {
        this.events = events;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.interval-ms:1000}")
    public void scheduledRun() {
        if (properties.enabled()) {
            publishPending();
        }
    }

    /** @return how many events were sent and marked published in this run */
    public int publishPending() {
        Integer sent = tx.execute(status -> publishBatch());
        return sent == null ? 0 : sent;
    }

    private int publishBatch() {
        int sent = 0;
        for (OutboxEvent event : events.lockUnpublished(PageRequest.of(0, properties.batchSize()))) {
            try {
                kafka.send(properties.topic(), event.getAggregateId().toString(), event.getPayload())
                        .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                event.markPublished();
                sent++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // stop at the first failure so events keep their order; the rest is retried on the next run
                log.warn("Cannot publish outbox event {} ({}), will retry: {}", event.getId(), event.getType(), e.toString());
                break;
            }
        }
        return sent;
    }
}
