package com.drawback.notification.service;

import com.drawback.events.ReviewEvent;
import com.drawback.notification.domain.Notification;
import com.drawback.notification.repo.NotificationRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationService {

    private final NotificationRepository repo;
    private final ChannelDispatcher dispatcher;

    public NotificationService(NotificationRepository repo, ChannelDispatcher dispatcher) {
        this.repo = repo;
        this.dispatcher = dispatcher;
    }

    /** Notify the subject owner that somebody reviewed their subject. Safe to call twice for the same event. */
    @Transactional
    public boolean onReviewCreated(ReviewEvent e) {
        if (e.type() != ReviewEvent.Type.REVIEW_CREATED) return false;
        if (e.subjectOwnerId().equals(e.authorId())) return false;

        String title = "New review on " + e.subjectName();
        String message = e.authorName() + " rated it " + e.rating() + "/100: " + abbreviate(e.title(), 120);
        String link = "/subjects/" + e.subjectId() + "/reviews/" + e.reviewId();

        int inserted = repo.insertIfAbsent(UUID.randomUUID(), e.eventId(), e.subjectOwnerId(), "REVIEW_CREATED", title, message, link);
        if (inserted == 0) return false; // redelivery

        // Fan-out only after the row is durably committed, so a rollback can never produce a phantom email/push.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                dispatcher.dispatch(e.subjectOwnerId(), title, message, link);
            }
        });
        return true;
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, int page, int size) {
        return repo.findByRecipientId(userId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50),
                Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repo.countByRecipientIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID id, UUID userId) {
        repo.markRead(id, userId); // idempotent; never reveals whether another user's id exists
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repo.markAllRead(userId);
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
