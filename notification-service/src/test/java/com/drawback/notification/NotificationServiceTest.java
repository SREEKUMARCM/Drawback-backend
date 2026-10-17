package com.drawback.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawback.events.ReviewEvent;
import com.drawback.notification.repo.NotificationRepository;
import com.drawback.notification.service.ChannelDispatcher;
import com.drawback.notification.service.NotificationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationServiceTest {

    final NotificationRepository repo = mock(NotificationRepository.class);
    final ChannelDispatcher dispatcher = mock(ChannelDispatcher.class);
    final NotificationService service = new NotificationService(repo, dispatcher);
    final UUID owner = UUID.randomUUID();

    @BeforeEach
    void startSynchronization() {
        TransactionSynchronizationManager.initSynchronization(); // stands in for an active @Transactional
    }

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clear();
    }

    private ReviewEvent created(UUID authorId, ReviewEvent.Type type) {
        return new ReviewEvent(UUID.randomUUID(), type, Instant.now(), UUID.randomUUID(), UUID.randomUUID(), "Phone X", "smartphones",
                owner, authorId, "alice", "Great phone", "body", 85, Instant.now(), 0);
    }

    private void commit() {
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    }

    @Test
    void notifiesTheSubjectOwnerAndDispatchesOnlyAfterCommit() {
        when(repo.insertIfAbsent(any(), any(), eq(owner), anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        assertThat(service.onReviewCreated(created(UUID.randomUUID(), ReviewEvent.Type.REVIEW_CREATED))).isTrue();
        verify(dispatcher, never()).dispatch(any(), any(), any(), any());   // not before the commit

        commit();
        verify(dispatcher).dispatch(eq(owner), eq("New review on Phone X"), anyString(), anyString());
    }

    @Test
    void redeliveredEventCreatesNothingAndSendsNothing() {
        when(repo.insertIfAbsent(any(), any(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(0);

        assertThat(service.onReviewCreated(created(UUID.randomUUID(), ReviewEvent.Type.REVIEW_CREATED))).isFalse();
        commit();
        verify(dispatcher, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void ownerIsNotNotifiedAboutTheirOwnReview() {
        assertThat(service.onReviewCreated(created(owner, ReviewEvent.Type.REVIEW_CREATED))).isFalse();
        verify(repo, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void onlyCreationEventsNotify() {
        assertThat(service.onReviewCreated(created(UUID.randomUUID(), ReviewEvent.Type.REVIEW_UPDATED))).isFalse();
        assertThat(service.onReviewCreated(created(UUID.randomUUID(), ReviewEvent.Type.REVIEW_DELETED))).isFalse();
    }
}
