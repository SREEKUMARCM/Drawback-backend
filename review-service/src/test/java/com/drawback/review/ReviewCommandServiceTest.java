package com.drawback.review;

import static com.drawback.review.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.drawback.events.ReviewEvent;
import com.drawback.events.Topics;
import com.drawback.grpc.user.UserProfile;
import com.drawback.grpc.user.UserStatus;
import com.drawback.review.client.UserDirectoryClient;
import com.drawback.review.client.UserDirectoryClient.Lookup;
import com.drawback.review.client.UserDirectoryClient.Outcome;
import com.drawback.review.config.ReviewConfig.CacheNames;
import com.drawback.review.domain.ReviewEntity;
import com.drawback.review.domain.SubjectEntity;
import com.drawback.review.outbox.OutboxService;
import com.drawback.review.repo.ReviewRepository;
import com.drawback.review.repo.SubjectRepository;
import com.drawback.review.service.ReviewCommandService;
import com.drawback.review.web.Dtos.ReviewRequest;
import com.drawback.security.ApiException;
import com.drawback.security.Role;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewCommandServiceTest {

    @Mock SubjectRepository subjects;
    @Mock ReviewRepository reviews;
    @Mock OutboxService outbox;
    @Mock UserDirectoryClient userDirectory;
    @Mock CacheManager caches;
    @Mock Cache reviewCache;
    @Mock Cache subjectCache;

    final UUID ownerId = UUID.randomUUID();
    final UUID reviewerId = UUID.randomUUID();
    SubjectEntity subject;
    ReviewCommandService service;

    @BeforeEach
    void setUp() {
        subject = new SubjectEntity("phone-abc123", "Phone X", "smartphones", "desc", ownerId);
        when(subjects.findById(subject.getId())).thenReturn(Optional.of(subject));
        when(reviews.save(any(ReviewEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(caches.getCache(CacheNames.REVIEWS)).thenReturn(reviewCache);
        when(caches.getCache(CacheNames.SUBJECTS)).thenReturn(subjectCache);
        activeAccount();
        service = newService(true);
    }

    private ReviewCommandService newService(boolean failOpen) {
        return new ReviewCommandService(subjects, reviews, outbox, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                userDirectory, caches, failOpen, 500);
    }

    private void activeAccount() {
        var profile = UserProfile.newBuilder().setStatus(UserStatus.ACTIVE).build();
        when(userDirectory.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.FOUND, profile));
    }

    private static ReviewRequest req(int rating) {
        return new ReviewRequest("Solid phone overall", "Battery lasts two days and the camera is great in daylight.", rating);
    }

    // ---- create -----------------------------------------------------------------------------------------------

    @Test
    void createAppliesAtomicDeltaAndWritesBothOutboxEventsAndEvictsCaches() {
        var res = service.create(subject.getId(), req(80), user(reviewerId, true));

        assertThat(res.rating()).isEqualTo(80);
        verify(subjects).applyDelta(subject.getId(), 1, 80);

        var topics = ArgumentCaptor.forClass(String.class);
        var payloads = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(2)).enqueue(topics.capture(), any(), any(), payloads.capture());
        assertThat(topics.getAllValues()).containsExactly(Topics.REVIEWS, Topics.SUBJECTS);
        assertThat(payloads.getAllValues().get(0)).isInstanceOfSatisfying(ReviewEvent.class, e -> {
            assertThat(e.type()).isEqualTo(ReviewEvent.Type.REVIEW_CREATED);
            assertThat(e.subjectOwnerId()).isEqualTo(ownerId);
            assertThat(e.authorId()).isEqualTo(reviewerId);
        });
        verify(reviewCache).evict(res.id().toString());
        verify(subjectCache).evict(subject.getId().toString());
    }

    @Test
    void unverifiedUserIsRejectedBeforeAnyRemoteCallOrWrite() {
        assertThatThrownBy(() -> service.create(subject.getId(), req(50), user(reviewerId, false)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verifyNoInteractions(userDirectory, outbox);
        verify(reviews, never()).save(any());
    }

    @Test
    void suspendedAccountIsRejectedEvenWithAStillValidToken() {
        var suspended = UserProfile.newBuilder().setStatus(UserStatus.SUSPENDED).build();
        when(userDirectory.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.FOUND, suspended));

        assertThatThrownBy(() -> service.create(subject.getId(), req(50), user(reviewerId, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(reviews, never()).save(any());
    }

    @Test
    void ownersCannotReviewTheirOwnSubject() {
        assertThatThrownBy(() -> service.create(subject.getId(), req(100), user(ownerId, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(subjects, never()).applyDelta(any(), anyLong(), anyLong());
    }

    @Test
    void secondReviewBySameUserIsAConflict() {
        when(reviews.existsBySubjectIdAndAuthorId(subject.getId(), reviewerId)).thenReturn(true);

        assertThatThrownBy(() -> service.create(subject.getId(), req(50), user(reviewerId, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(outbox, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    void unknownSubjectIs404() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), req(50), user(reviewerId, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void authServiceOutageFailsOpenByDefault() {
        when(userDirectory.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.UNAVAILABLE, null));

        assertThat(service.create(subject.getId(), req(70), user(reviewerId, true)).rating()).isEqualTo(70);
    }

    @Test
    void authServiceOutageFailsClosedWhenConfigured() {
        when(userDirectory.find(any(), any(), anyLong())).thenReturn(new Lookup(Outcome.UNAVAILABLE, null));
        var strict = newService(false);

        assertThatThrownBy(() -> strict.create(subject.getId(), req(70), user(reviewerId, true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    // ---- update / delete --------------------------------------------------------------------------------------

    private ReviewEntity existingReview(int rating) {
        var r = new ReviewEntity(subject.getId(), reviewerId, "rev", "Old title", "Old body that is long enough to be valid.", rating);
        when(reviews.findById(r.getId())).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void updateAdjustsAggregateByTheRatingDifference() {
        var r = existingReview(40);

        service.update(r.getId(), req(90), user(reviewerId, true));

        verify(subjects).applyDelta(subject.getId(), 0, 50);
        verify(outbox, times(2)).enqueue(any(), any(), any(), any());
    }

    @Test
    void updateWithSameRatingTouchesNoAggregate() {
        var r = existingReview(60);

        service.update(r.getId(), req(60), user(reviewerId, true));

        verify(subjects, never()).applyDelta(any(), anyLong(), anyLong());
    }

    @Test
    void onlyTheAuthorCanEdit() {
        var r = existingReview(60);

        assertThatThrownBy(() -> service.update(r.getId(), req(10), user(UUID.randomUUID(), true, Role.MODERATOR)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void deleteRemovesRatingFromAggregateAndEmitsDeletedEvent() {
        var r = existingReview(75);

        service.delete(r.getId(), user(reviewerId, true));

        verify(reviews).delete(r);
        verify(subjects).applyDelta(eq(subject.getId()), eq(-1L), eq(-75L));
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(outbox, times(2)).enqueue(any(), any(), any(), payload.capture());
        assertThat(((ReviewEvent) payload.getAllValues().get(0)).type()).isEqualTo(ReviewEvent.Type.REVIEW_DELETED);
    }

    @Test
    void moderatorCanDeleteSomeoneElsesReviewButPlainUserCannot() {
        var r = existingReview(20);

        assertThatThrownBy(() -> service.delete(r.getId(), user(UUID.randomUUID(), true)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));

        service.delete(r.getId(), user(UUID.randomUUID(), true, Role.MODERATOR));
        verify(reviews).delete(r);
    }
}
