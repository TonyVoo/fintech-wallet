package com.fintech.wallet.transaction.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.transaction.domain.Transfer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Records "this transfer reached a final state" as a row in the outbox table.
 *
 * <p>{@code MANDATORY} makes it impossible to call this outside a transaction: the whole point is that the event
 * row commits or rolls back together with the state change, so it can never be lost or exist without its cause.
 */
@Component
public class OutboxWriter {

    private final OutboxEventRepository events;
    private final ObjectMapper mapper;

    public OutboxWriter(OutboxEventRepository events, ObjectMapper mapper) {
        this.events = events;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Transfer t) {
        String type = switch (t.getStatus()) {
            case COMPLETED -> "TRANSFER_COMPLETED";
            case FAILED -> "TRANSFER_FAILED";
            case REFUNDED -> "TRANSFER_REFUNDED";
            default -> throw new IllegalStateException("No event for in-flight status " + t.getStatus());
        };
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        TransferEvent event = new TransferEvent(eventId, type, now, t.getId(), t.getUserId(), t.getToUserId(),
                t.getFromAccountId(), t.getToAccountId(), t.getAmount(), t.getCurrency(), t.getStatus().name(),
                t.getFailureReason() == null ? null : t.getFailureReason().name());
        try {
            events.save(new OutboxEvent(eventId, t.getId(), type, mapper.writeValueAsString(event), now));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + type + " for transfer " + t.getId(), e);
        }
    }
}
