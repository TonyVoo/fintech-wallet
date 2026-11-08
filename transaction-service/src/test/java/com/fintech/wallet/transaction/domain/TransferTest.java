package com.fintech.wallet.transaction.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private Transfer newTransfer() {
        return new Transfer(UUID.randomUUID(), "key-1", from, to, UUID.randomUUID(),
                new BigDecimal("100.00"), "VND", null);
    }

    @Test
    void happyPathGoesPendingDebitedCompleted() {
        Transfer t = newTransfer();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);

        t.markDebited();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.DEBITED);

        t.markCompleted();
        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getFailureReason()).isNull();
    }

    @Test
    void refusedDebitEndsInFailedWithAReason() {
        Transfer t = newTransfer();
        t.markFailed(FailureReason.INSUFFICIENT_FUNDS);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getFailureReason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
    }

    @Test
    void compensationEndsInRefunded() {
        Transfer t = newTransfer();
        t.markDebited();
        t.markRefunded(FailureReason.DESTINATION_ACCOUNT_UNAVAILABLE);

        assertThat(t.getStatus()).isEqualTo(TransferStatus.REFUNDED);
    }

    @Test
    void impossibleTransitionsAreRejected() {
        // cannot complete or refund money that was never debited
        assertThatThrownBy(() -> newTransfer().markCompleted()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newTransfer().markRefunded(FailureReason.DESTINATION_ACCOUNT_UNAVAILABLE))
                .isInstanceOf(IllegalStateException.class);

        // cannot fail a transfer after the source was debited: it must be refunded instead
        Transfer debited = newTransfer();
        debited.markDebited();
        assertThatThrownBy(() -> debited.markFailed(FailureReason.INSUFFICIENT_FUNDS))
                .isInstanceOf(IllegalStateException.class);

        // terminal states are final
        Transfer done = newTransfer();
        done.markDebited();
        done.markCompleted();
        assertThatThrownBy(done::markDebited).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> done.markRefunded(FailureReason.DESTINATION_ACCOUNT_UNAVAILABLE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyInFlightStatesAreNonTerminal() {
        assertThat(TransferStatus.PENDING.isTerminal()).isFalse();
        assertThat(TransferStatus.DEBITED.isTerminal()).isFalse();
        assertThat(TransferStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TransferStatus.FAILED.isTerminal()).isTrue();
        assertThat(TransferStatus.REFUNDED.isTerminal()).isTrue();
    }

    @Test
    void sameRequestMatchesAndAnyDifferenceDoesNot() {
        Transfer t = newTransfer();

        assertThat(t.matches(from, to, new BigDecimal("100"))).isTrue(); // 100 == 100.00
        assertThat(t.matches(from, to, new BigDecimal("100.01"))).isFalse();
        assertThat(t.matches(to, from, new BigDecimal("100"))).isFalse();
        assertThat(t.matches(from, UUID.randomUUID(), new BigDecimal("100"))).isFalse();
    }

    @Test
    void operationIdsAreStablePerTransferAndDistinctPerStep() {
        Transfer a = newTransfer();
        Transfer b = newTransfer();

        assertThat(a.debitOperationId()).isEqualTo(a.debitOperationId()); // same on every retry
        assertThat(a.debitOperationId()).isNotEqualTo(b.debitOperationId());
        assertThat(java.util.Set.of(a.debitOperationId(), a.creditOperationId(), a.refundOperationId())).hasSize(3);
    }
}
