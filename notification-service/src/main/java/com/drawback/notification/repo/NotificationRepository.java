package com.drawback.notification.repo;

import com.drawback.notification.domain.Notification;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Returns 1 if inserted, 0 if this (event, recipient) pair already exists (redelivery). */
    @Modifying
    @Query(value = """
            INSERT INTO notification (id, event_id, recipient_id, type, title, message, link, created_at)
            VALUES (:id, :eventId, :recipientId, :type, :title, :message, :link, now())
            ON CONFLICT (event_id, recipient_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(UUID id, UUID eventId, UUID recipientId, String type, String title, String message, String link);

    Page<Notification> findByRecipientId(UUID recipientId, Pageable pageable);

    long countByRecipientIdAndReadAtIsNull(UUID recipientId);

    @Modifying
    @Query("update Notification n set n.readAt = instant where n.id = :id and n.recipientId = :recipientId and n.readAt is null")
    int markRead(UUID id, UUID recipientId);

    @Modifying
    @Query("update Notification n set n.readAt = instant where n.recipientId = :recipientId and n.readAt is null")
    int markAllRead(UUID recipientId);
}
