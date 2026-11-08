package com.fintech.wallet.notification.messaging;

import com.fintech.wallet.notification.service.NotificationService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransferEventListener {

    private final NotificationService service;

    public TransferEventListener(NotificationService service) {
        this.service = service;
    }

    /**
     * The offset is committed only after this method returns, so a crash mid-processing means the message is
     * delivered again (at-least-once). {@link NotificationService#handle} is idempotent, which makes that harmless.
     */
    @KafkaListener(topics = "${app.kafka.topic}")
    public void onTransferEvent(String payload) {
        service.handle(payload);
    }
}
