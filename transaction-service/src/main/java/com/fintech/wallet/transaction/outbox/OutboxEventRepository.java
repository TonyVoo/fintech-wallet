package com.fintech.wallet.transaction.outbox;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest unpublished events, locked with SELECT ... FOR UPDATE SKIP LOCKED (lock timeout -2): when several
     * instances of the service run, each takes a different batch instead of sending the same events twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select e from OutboxEvent e where e.publishedAt is null order by e.createdAt asc")
    List<OutboxEvent> lockUnpublished(Pageable page);

    long countByPublishedAtIsNull();

    List<OutboxEvent> findByAggregateId(UUID aggregateId);
}
