package com.fintech.wallet.notification.repository;

import com.fintech.wallet.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    boolean existsByEventId(UUID eventId);

    List<Notification> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Notification> findByEventId(UUID eventId);
}
