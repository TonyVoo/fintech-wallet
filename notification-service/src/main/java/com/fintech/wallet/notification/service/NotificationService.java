package com.fintech.wallet.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.notification.domain.Notification;
import com.fintech.wallet.notification.messaging.MalformedEventException;
import com.fintech.wallet.notification.messaging.TransferEvent;
import com.fintech.wallet.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final ObjectMapper mapper;

    public NotificationService(NotificationRepository notifications, ObjectMapper mapper) {
        this.notifications = notifications;
        this.mapper = mapper;
    }

    /**
     * Turns one Kafka message into notifications. Safe to call again with the same message:
     * Kafka delivers at-least-once, so duplicates are normal and must change nothing.
     *
     * @throws MalformedEventException if the message is not a valid event (retrying cannot help)
     */
    @Transactional
    public void handle(String payload) {
        TransferEvent event = parse(payload);

        if (notifications.existsByEventId(event.eventId())) {
            log.info("Event {} already processed, skipping", event.eventId());
            return;
        }
        List<Notification> created = toNotifications(event);
        try {
            notifications.saveAllAndFlush(created);
        } catch (DataIntegrityViolationException e) {
            // an identical event was committed between the check above and the insert: it is a duplicate
            log.info("Event {} was processed concurrently, skipping", event.eventId());
            return;
        }
        created.forEach(n -> log.info("NOTIFY user={} kind={} message=\"{}\"", n.getUserId(), n.getKind(), n.getMessage()));
    }

    @Transactional(readOnly = true)
    public List<Notification> listMine(UUID userId) {
        return notifications.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    private TransferEvent parse(String payload) {
        TransferEvent event;
        try {
            event = mapper.readValue(payload, TransferEvent.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new MalformedEventException("Not a valid transfer event", e);
        }
        if (event == null || event.eventId() == null || event.type() == null || event.transferId() == null
                || event.fromUserId() == null || event.amount() == null) {
            throw new MalformedEventException("Transfer event is missing required fields");
        }
        return event;
    }

    private List<Notification> toNotifications(TransferEvent e) {
        String money = e.amount().stripTrailingZeros().toPlainString() + " " + e.currency();
        List<Notification> result = new ArrayList<>();
        switch (e.type()) {
            case "TRANSFER_COMPLETED" -> {
                result.add(new Notification(e.eventId(), e.fromUserId(), e.transferId(), "TRANSFER_SENT",
                        "You sent " + money + "."));
                if (e.toUserId() != null && !e.toUserId().equals(e.fromUserId())) {
                    result.add(new Notification(e.eventId(), e.toUserId(), e.transferId(), "TRANSFER_RECEIVED",
                            "You received " + money + "."));
                }
            }
            case "TRANSFER_FAILED" -> result.add(new Notification(e.eventId(), e.fromUserId(), e.transferId(),
                    "TRANSFER_FAILED", "Your transfer of " + money + " failed: " + describe(e.failureReason()) + "."));
            case "TRANSFER_REFUNDED" -> result.add(new Notification(e.eventId(), e.fromUserId(), e.transferId(),
                    "TRANSFER_REFUNDED", "Your transfer of " + money + " could not be delivered and was refunded."));
            default -> log.warn("Ignoring event {} of unknown type {}", e.eventId(), e.type()); // newer producer
        }
        return result;
    }

    private static String describe(String reason) {
        if (reason == null) {
            return "unknown reason";
        }
        return switch (reason) {
            case "INSUFFICIENT_FUNDS" -> "insufficient funds";
            case "SOURCE_ACCOUNT_NOT_FOUND" -> "source account not found";
            case "DESTINATION_ACCOUNT_UNAVAILABLE" -> "destination account unavailable";
            default -> reason.toLowerCase().replace('_', ' ');
        };
    }
}
