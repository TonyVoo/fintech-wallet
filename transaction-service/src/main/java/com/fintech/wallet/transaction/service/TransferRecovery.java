package com.fintech.wallet.transaction.service;

import com.fintech.wallet.transaction.domain.Transfer;
import com.fintech.wallet.transaction.domain.TransferStatus;
import com.fintech.wallet.transaction.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Finishes transfers that were interrupted: the service crashed between two steps, or account-service did not
 * answer. They are found by their state (PENDING / DEBITED) and age, then simply advanced again. That is
 * safe because every step is idempotent (see {@link TransferOrchestrator}).
 */
@Component
public class TransferRecovery {

    private static final Logger log = LoggerFactory.getLogger(TransferRecovery.class);

    private final TransferRepository transfers;
    private final TransferOrchestrator orchestrator;
    private final RecoveryProperties properties;

    public TransferRecovery(TransferRepository transfers, TransferOrchestrator orchestrator, RecoveryProperties properties) {
        this.transfers = transfers;
        this.orchestrator = orchestrator;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.recovery.interval-ms:15000}")
    public void scheduledRun() {
        if (properties.enabled()) {
            recoverStuck(properties.staleAfter());
        }
    }

    /** @return how many stuck transfers were looked at */
    public int recoverStuck(Duration staleAfter) {
        List<Transfer> stuck = transfers.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                List.of(TransferStatus.PENDING, TransferStatus.DEBITED),
                Instant.now().minus(staleAfter),
                PageRequest.of(0, properties.batchSize()));
        for (Transfer transfer : stuck) {
            try {
                Transfer result = orchestrator.advance(transfer.getId());
                log.info("Recovery: transfer {} {} -> {}", transfer.getId(), transfer.getStatus(), result.getStatus());
            } catch (RuntimeException e) {
                // one broken transfer must not stop the others
                log.error("Recovery of transfer {} failed", transfer.getId(), e);
            }
        }
        return stuck.size();
    }
}
