package com.fintech.wallet.transaction.service;

import com.fintech.wallet.transaction.client.AccountClient;
import com.fintech.wallet.transaction.client.AccountClient.OperationResult;
import com.fintech.wallet.transaction.client.RemoteUnavailableException;
import com.fintech.wallet.transaction.domain.FailureReason;
import com.fintech.wallet.transaction.domain.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Orchestrated saga: moves a transfer through its steps, calling account-service for each one.
 *
 * <pre>
 *   PENDING : debit the source            -> DEBITED   (or FAILED if the debit is refused)
 *   DEBITED : credit the destination      -> COMPLETED (or refund the source -> REFUNDED if the destination is gone)
 * </pre>
 *
 * <p>Why this is safe to run more than once, from several threads, or again after a crash:
 * <ul>
 *   <li>every call to account-service carries an operation id derived from the transfer, which
 *       account-service applies at most once;</li>
 *   <li>every state change locks the transfer row and is idempotent (see {@link TransferStore}), so two actors
 *       advancing the same transfer take turns and the second one just sees the result;</li>
 *   <li>when account-service gives no answer, the transfer is simply left where it is. That is never treated
 *       as a failure, because the call may have been applied. The recovery job retries later with the same ids.</li>
 * </ul>
 */
@Service
public class TransferOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TransferOrchestrator.class);

    private final TransferStore store;
    private final AccountClient accounts;

    public TransferOrchestrator(TransferStore store, AccountClient accounts) {
        this.store = store;
        this.accounts = accounts;
    }

    /** Drives the transfer as far as it can go right now and returns its latest state. */
    public Transfer advance(UUID transferId) {
        Transfer transfer = store.get(transferId);
        try {
            while (!transfer.getStatus().isTerminal()) {
                transfer = step(transfer);
            }
        } catch (RemoteUnavailableException e) {
            log.warn("Transfer {} paused in {}: {}", transferId, transfer.getStatus(), e.getMessage());
        }
        return store.get(transferId);
    }

    private Transfer step(Transfer t) {
        return switch (t.getStatus()) {
            case PENDING -> debitSource(t);
            case DEBITED -> creditDestination(t);
            default -> throw new IllegalStateException("Nothing to do for " + t.getStatus());
        };
    }

    private Transfer debitSource(Transfer t) {
        OperationResult result = accounts.debit(t.getFromAccountId(), t.debitOperationId(), t.getAmount());
        return switch (result) {
            case APPLIED -> store.markDebited(t.getId());
            case INSUFFICIENT_FUNDS -> store.markFailed(t.getId(), FailureReason.INSUFFICIENT_FUNDS);
            case ACCOUNT_NOT_FOUND -> store.markFailed(t.getId(), FailureReason.SOURCE_ACCOUNT_NOT_FOUND);
        };
    }

    private Transfer creditDestination(Transfer t) {
        OperationResult result = accounts.credit(t.getToAccountId(), t.creditOperationId(), t.getAmount());
        return switch (result) {
            case APPLIED -> store.markCompleted(t.getId());
            case ACCOUNT_NOT_FOUND -> compensate(t);
            case INSUFFICIENT_FUNDS -> throw new IllegalStateException("A credit can never be refused for lack of funds");
        };
    }

    /** Compensating action: the money already left the source, so give it back. */
    private Transfer compensate(Transfer t) {
        OperationResult refund = accounts.credit(t.getFromAccountId(), t.refundOperationId(), t.getAmount());
        if (refund != OperationResult.APPLIED) {
            // both accounts are unusable: needs a human, but the transfer stays DEBITED so nothing is silently lost
            throw new IllegalStateException("Cannot refund transfer " + t.getId() + ": source account " + refund);
        }
        return store.markRefunded(t.getId(), FailureReason.DESTINATION_ACCOUNT_UNAVAILABLE);
    }
}
