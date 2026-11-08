package com.fintech.wallet.transaction.service;

import com.fintech.wallet.transaction.client.AccountClient;
import com.fintech.wallet.transaction.client.AccountClient.AccountInfo;
import com.fintech.wallet.transaction.domain.Transfer;
import com.fintech.wallet.transaction.repository.TransferRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class TransferService {

    private final TransferStore store;
    private final TransferOrchestrator orchestrator;
    private final AccountClient accounts;
    private final TransferRepository transfers;

    public TransferService(TransferStore store, TransferOrchestrator orchestrator, AccountClient accounts,
                           TransferRepository transfers) {
        this.store = store;
        this.orchestrator = orchestrator;
        this.accounts = accounts;
        this.transfers = transfers;
    }

    /**
     * Not {@code @Transactional}: this method calls other services, and no database transaction may stay open
     * during a network call. The orchestrator and the store handle their own short transactions.
     */
    public TransferResult transfer(UUID userId, String idempotencyKey, UUID from, UUID to,
                                   BigDecimal amount, String description) {
        if (from.equals(to)) {
            throw TransferRejectedException.invalid("Source and destination must be different accounts");
        }

        // A repeated Idempotency-Key never creates a second transfer; it returns (and keeps driving) the first one.
        var existing = store.find(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), from, to, amount);
        }

        AccountInfo source = accounts.getAccount(from)
                .filter(a -> a.ownerId().equals(userId))
                .orElseThrow(TransferRejectedException::sourceNotFound);
        AccountInfo destination = accounts.getAccount(to)
                .orElseThrow(TransferRejectedException::destinationNotFound);
        if (!source.currency().equals(destination.currency())) {
            throw TransferRejectedException.currencyMismatch();
        }

        Transfer created;
        try {
            created = store.createPending(userId, idempotencyKey, from, to, amount, source.currency(), description);
        } catch (DataIntegrityViolationException e) {
            // two requests with the same key arrived together and the other one won the insert
            Transfer winner = store.find(userId, idempotencyKey).orElseThrow(() -> e);
            return replay(winner, from, to, amount);
        }
        return new TransferResult(orchestrator.advance(created.getId()), true);
    }

    private TransferResult replay(Transfer existing, UUID from, UUID to, BigDecimal amount) {
        if (!existing.matches(from, to, amount)) {
            throw TransferRejectedException.idempotencyKeyReused();
        }
        return new TransferResult(orchestrator.advance(existing.getId()), false);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID userId, UUID transferId) {
        return transfers.findByIdAndUserId(transferId, userId).orElseThrow(TransferRejectedException::transferNotFound);
    }

    @Transactional(readOnly = true)
    public List<Transfer> listMine(UUID userId) {
        return transfers.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
