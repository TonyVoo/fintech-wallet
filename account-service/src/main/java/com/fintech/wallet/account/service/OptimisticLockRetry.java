package com.fintech.wallet.account.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Re-runs an action when it loses an optimistic-locking race.
 *
 * <p>The action must run a WHOLE transaction (read, change, commit): the version conflict is only
 * detected at commit, and a failed transaction cannot be reused. That is why this cannot be done
 * with a retry loop inside a {@code @Transactional} method; the loop has to wrap the transaction.
 *
 * <p>Only lock conflicts are retried. Business errors (insufficient funds, not found) are not,
 * because repeating them would give the same answer.
 */
@Component
public class OptimisticLockRetry {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockRetry.class);

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;

    public OptimisticLockRetry() {
        this(8, 10, 200);
    }

    public OptimisticLockRetry(int maxAttempts, long baseDelayMs, long maxDelayMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public <T> T execute(Supplier<T> action) {
        int attempt = 1;
        while (true) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException e) {
                if (attempt >= maxAttempts) {
                    log.warn("Giving up after {} attempts: {}", attempt, e.getMessage());
                    throw e;
                }
                log.debug("Optimistic lock conflict, retrying (attempt {}/{})", attempt, maxAttempts);
                sleep(backoff(attempt));
                attempt++;
            }
        }
    }

    /** Exponential backoff with full jitter: concurrent losers spread out instead of colliding again. */
    private long backoff(int attempt) {
        long cap = Math.min(maxDelayMs, baseDelayMs << Math.min(attempt - 1, 20));
        return ThreadLocalRandom.current().nextLong(cap + 1);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry", e);
        }
    }
}
