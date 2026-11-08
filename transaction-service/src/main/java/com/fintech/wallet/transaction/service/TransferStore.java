package com.fintech.wallet.transaction.service;

import com.fintech.wallet.transaction.domain.EntryDirection;
import com.fintech.wallet.transaction.domain.FailureReason;
import com.fintech.wallet.transaction.domain.LedgerEntry;
import com.fintech.wallet.transaction.domain.Transfer;
import com.fintech.wallet.transaction.domain.TransferStatus;
import com.fintech.wallet.transaction.repository.LedgerEntryRepository;
import com.fintech.wallet.transaction.repository.TransferRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Every state change of a transfer is one short database transaction.
 *
 * <p>The orchestrator calls another service between these steps, and a database transaction must never stay
 * open across a network call. So each step commits on its own, and a crash in between leaves the transfer in a
 * recorded intermediate state (PENDING, DEBITED) that the recovery job picks up.
 *
 * <p>Each transition locks the transfer row first ({@code SELECT ... FOR UPDATE}), so concurrent actors take
 * turns, and is idempotent: if the transfer has already moved past the expected state (another request or the
 * recovery job got there first) the current state is returned and nothing changes. The ledger entries are
 * written in the same transaction as the COMPLETED transition, so they exist exactly once, or not at all.
 */
@Component
public class TransferStore {

    private final TransferRepository transfers;
    private final LedgerEntryRepository ledger;

    public TransferStore(TransferRepository transfers, LedgerEntryRepository ledger) {
        this.transfers = transfers;
        this.ledger = ledger;
    }

    @Transactional
    public Transfer createPending(UUID userId, String idempotencyKey, UUID from, UUID to,
                                  BigDecimal amount, String currency, String description) {
        // the unique (user_id, idempotency_key) constraint rejects a concurrent duplicate with
        // DataIntegrityViolationException; the caller then loads the winner
        return transfers.saveAndFlush(new Transfer(userId, idempotencyKey, from, to, amount, currency, description));
    }

    @Transactional(readOnly = true)
    public Optional<Transfer> find(UUID userId, String idempotencyKey) {
        return transfers.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Transfer get(UUID id) {
        return transfers.findById(id).orElseThrow(() -> new IllegalStateException("Transfer " + id + " vanished"));
    }

    @Transactional
    public Transfer markDebited(UUID id) {
        Transfer t = lock(id);
        if (t.getStatus() == TransferStatus.PENDING) {
            t.markDebited();
        }
        return t;
    }

    /** Completes the transfer and books the two ledger entries in the same transaction. */
    @Transactional
    public Transfer markCompleted(UUID id) {
        Transfer t = lock(id);
        if (t.getStatus() == TransferStatus.DEBITED) {
            t.markCompleted();
            ledger.save(new LedgerEntry(t.getId(), t.getFromAccountId(), EntryDirection.DEBIT, t.getAmount(), t.getCurrency()));
            ledger.save(new LedgerEntry(t.getId(), t.getToAccountId(), EntryDirection.CREDIT, t.getAmount(), t.getCurrency()));
        }
        return t;
    }

    @Transactional
    public Transfer markFailed(UUID id, FailureReason reason) {
        Transfer t = lock(id);
        if (t.getStatus() == TransferStatus.PENDING) {
            t.markFailed(reason);
        }
        return t;
    }

    @Transactional
    public Transfer markRefunded(UUID id, FailureReason reason) {
        Transfer t = lock(id);
        if (t.getStatus() == TransferStatus.DEBITED) {
            t.markRefunded(reason);
        }
        return t;
    }

    private Transfer lock(UUID id) {
        return transfers.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalStateException("Transfer " + id + " vanished"));
    }
}
