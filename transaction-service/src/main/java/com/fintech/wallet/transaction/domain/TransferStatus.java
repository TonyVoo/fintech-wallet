package com.fintech.wallet.transaction.domain;

/**
 * <pre>
 *   PENDING --debit ok--> DEBITED --credit ok--------------> COMPLETED
 *      |                     |
 *      | debit refused       | destination cannot be credited, source is refunded
 *      v                     v
 *    FAILED               REFUNDED
 * </pre>
 * PENDING and DEBITED are "in flight": money may have left the source account but the transfer is not
 * finished. The recovery job keeps driving those forward.
 */
public enum TransferStatus {
    PENDING,
    DEBITED,
    COMPLETED,
    FAILED,
    REFUNDED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REFUNDED;
    }
}
