package com.fintech.wallet.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.wallet.notification.domain.Notification;
import com.fintech.wallet.notification.messaging.MalformedEventException;
import com.fintech.wallet.notification.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService service =
            new NotificationService(repository, new ObjectMapper().findAndRegisterModules());

    private final UUID eventId = UUID.randomUUID();
    private final UUID sender = UUID.randomUUID();
    private final UUID receiver = UUID.randomUUID();

    private String event(String type, UUID toUser, String amount, String reason) {
        return "{\"eventId\":\"" + eventId + "\",\"type\":\"" + type + "\",\"transferId\":\"" + UUID.randomUUID()
                + "\",\"fromUserId\":\"" + sender + "\",\"toUserId\":" + (toUser == null ? "null" : "\"" + toUser + "\"")
                + ",\"amount\":" + amount + ",\"currency\":\"VND\",\"status\":\"X\",\"failureReason\":"
                + (reason == null ? "null" : "\"" + reason + "\"") + ",\"someFutureField\":123}";
    }

    @SuppressWarnings("unchecked")
    private List<Notification> saved() {
        ArgumentCaptor<List<Notification>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAllAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void completedTransferNotifiesBothSenderAndReceiver() {
        service.handle(event("TRANSFER_COMPLETED", receiver, "300.0000", null));

        List<Notification> saved = saved();
        assertThat(saved).hasSize(2);
        assertThat(saved).anySatisfy(n -> {
            assertThat(n.getUserId()).isEqualTo(sender);
            assertThat(n.getKind()).isEqualTo("TRANSFER_SENT");
            assertThat(n.getMessage()).isEqualTo("You sent 300 VND."); // trailing zeros trimmed
        });
        assertThat(saved).anySatisfy(n -> {
            assertThat(n.getUserId()).isEqualTo(receiver);
            assertThat(n.getKind()).isEqualTo("TRANSFER_RECEIVED");
        });
        assertThat(saved).allMatch(n -> n.getEventId().equals(eventId));
    }

    @Test
    void transferToOneselfNotifiesOnlyOnce() {
        service.handle(event("TRANSFER_COMPLETED", sender, "10", null));

        assertThat(saved()).hasSize(1);
    }

    @Test
    void failedTransferNotifiesOnlyTheSenderWithTheReason() {
        service.handle(event("TRANSFER_FAILED", receiver, "500", "INSUFFICIENT_FUNDS"));

        assertThat(saved()).singleElement().satisfies(n -> {
            assertThat(n.getUserId()).isEqualTo(sender);
            assertThat(n.getMessage()).isEqualTo("Your transfer of 500 VND failed: insufficient funds.");
        });
    }

    @Test
    void refundedTransferNotifiesTheSender() {
        service.handle(event("TRANSFER_REFUNDED", receiver, "500", "DESTINATION_ACCOUNT_UNAVAILABLE"));

        assertThat(saved()).singleElement().satisfies(n -> assertThat(n.getKind()).isEqualTo("TRANSFER_REFUNDED"));
    }

    @Test
    void anEventThatWasAlreadyProcessedChangesNothing() {
        when(repository.existsByEventId(eventId)).thenReturn(true);

        service.handle(event("TRANSFER_COMPLETED", receiver, "300", null));

        verify(repository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void aRaceWithAnIdenticalEventIsTreatedAsADuplicateNotAnError() {
        when(repository.saveAllAndFlush(anyList())).thenThrow(new DataIntegrityViolationException("uq_notifications_event_user"));

        // must not throw, otherwise Kafka would retry and then dead-letter a perfectly good message
        service.handle(event("TRANSFER_COMPLETED", receiver, "300", null));
    }

    @Test
    void unknownEventTypesAreIgnoredSoNewerProducersDoNotBreakThisConsumer() {
        service.handle(event("TRANSFER_SOMETHING_NEW", receiver, "300", null));

        verify(repository).saveAllAndFlush(List.of());
    }

    @Test
    void messagesThatCanNeverBeProcessedAreReportedAsMalformed() {
        assertThatThrownBy(() -> service.handle("this is not json")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> service.handle("{}")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> service.handle("null")).isInstanceOf(MalformedEventException.class);
        assertThatThrownBy(() -> service.handle("{\"eventId\":\"not-a-uuid\"}")).isInstanceOf(MalformedEventException.class);
    }
}
