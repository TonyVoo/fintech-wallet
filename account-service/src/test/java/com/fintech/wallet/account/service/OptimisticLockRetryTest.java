package com.fintech.wallet.account.service;

import com.fintech.wallet.account.domain.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OptimisticLockRetryTest {

    private final OptimisticLockRetry retry = new OptimisticLockRetry(5, 0, 0);

    @Test
    void succeedsAfterTransientConflicts() {
        AtomicInteger calls = new AtomicInteger();

        String result = retry.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new OptimisticLockingFailureException("conflict");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterMaxAttemptsAndRethrows() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new OptimisticLockingFailureException("always conflicts");
        })).isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(calls).hasValue(5);
    }

    @Test
    void businessErrorsAreNotRetried() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> retry.execute(() -> {
            calls.incrementAndGet();
            throw new InsufficientFundsException("123");
        })).isInstanceOf(InsufficientFundsException.class);

        assertThat(calls).hasValue(1);
    }

    @Test
    void firstTrySuccessRunsOnce() {
        AtomicInteger calls = new AtomicInteger();
        retry.execute(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }
}
