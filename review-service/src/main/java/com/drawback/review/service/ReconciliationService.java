package com.drawback.review.service;

import com.drawback.events.SubjectEvent;
import com.drawback.events.Topics;
import com.drawback.review.config.ReviewConfig.CacheNames;
import com.drawback.review.outbox.OutboxService;
import com.drawback.review.repo.ReviewRepository;
import com.drawback.review.repo.SubjectRepository;
import com.drawback.review.web.Dtos.ReconcileReport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Safety net for the denormalised aggregate: recomputes (count, sum) from the review rows and repairs drift.
 * Subjects are split into chunks processed in parallel on the bounded batch pool; each subject is repaired in its own
 * short transaction under a row lock, so it is safe to run while traffic continues.
 * (Run on ONE instance only: trigger it from a Kubernetes CronJob hitting the admin endpoint, or add ShedLock.)
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final int CHUNK = 200;

    private final SubjectRepository subjects;
    private final ReviewRepository reviews;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Executor batch;
    private final CacheManager caches;

    public ReconciliationService(SubjectRepository subjects, ReviewRepository reviews, OutboxService outbox,
                                 TransactionTemplate tx, @Qualifier("batchExecutor") Executor batch, CacheManager caches) {
        this.subjects = subjects;
        this.reviews = reviews;
        this.outbox = outbox;
        this.tx = tx;
        this.batch = batch;
        this.caches = caches;
    }

    @Scheduled(cron = "${drawback.reconcile.cron:-}", zone = "UTC")
    void scheduled() {
        log.info("Scheduled reconciliation: {}", reconcileAll());
    }

    public ReconcileReport reconcileAll() {
        long started = System.currentTimeMillis();
        List<CompletableFuture<Integer>> chunks = new ArrayList<>();
        int checked = 0;
        Pageable page = PageRequest.of(0, CHUNK);
        Slice<UUID> slice;
        do {
            slice = subjects.findIds(page);
            List<UUID> ids = slice.getContent();
            checked += ids.size();
            chunks.add(CompletableFuture.supplyAsync(() -> ids.stream().mapToInt(this::reconcileOne).sum(), batch));
            page = slice.nextPageable();
        } while (slice.hasNext());

        int corrected = chunks.stream().mapToInt(CompletableFuture::join).sum();
        return new ReconcileReport(checked, corrected, System.currentTimeMillis() - started);
    }

    /** @return 1 if the subject's aggregate had drifted and was repaired, else 0 */
    int reconcileOne(UUID subjectId) {
        Integer fixed = tx.execute(status -> {
            var subject = subjects.lockById(subjectId).orElse(null); // SELECT ... FOR UPDATE
            if (subject == null) return 0;
            var truth = reviews.aggregateOf(subjectId);
            if (truth.getCnt() == subject.getReviewCount() && truth.getTotal() == subject.getRatingSum()) return 0;
            log.warn("Aggregate drift on subject {}: stored=({}, {}) actual=({}, {})", subjectId,
                    subject.getReviewCount(), subject.getRatingSum(), truth.getCnt(), truth.getTotal());
            subject.overwriteAggregate(truth.getCnt(), truth.getTotal());
            var event = EventFactory.subject(SubjectEvent.Type.SUBJECT_STATS_CHANGED, subject);
            outbox.enqueue(Topics.SUBJECTS, subjectId.toString(), event.eventId(), event);
            return 1;
        });
        if (fixed != null && fixed == 1) {
            var cache = caches.getCache(CacheNames.SUBJECTS);
            if (cache != null) cache.evict(subjectId.toString());
        }
        return fixed == null ? 0 : fixed;
    }
}
