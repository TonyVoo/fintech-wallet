package com.fintech.wallet.transaction.repository;

import com.fintech.wallet.transaction.domain.Transfer;
import com.fintech.wallet.transaction.domain.TransferStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    /**
     * SELECT ... FOR UPDATE. Used for every state change, so two actors moving the same transfer
     * (a request and the recovery job, or two duplicate requests) take turns instead of colliding.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);

    Optional<Transfer> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Optional<Transfer> findByIdAndUserId(UUID id, UUID userId);

    List<Transfer> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Transfer> findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            Collection<TransferStatus> statuses, Instant updatedBefore, Pageable page);
}
