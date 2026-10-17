package com.drawback.review.outbox;

import com.drawback.review.domain.OutboxEvent;
import com.drawback.review.repo.OutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Relays committed outbox rows to Kafka (at-least-once; consumers are idempotent).
 * A batch is sent concurrently (the producer pipelines the sends) and the DB rows are only marked published after the
 * broker acknowledged them. If a send fails, later events with the SAME key are left for the next poll so per-key
 * ordering is preserved.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final int batchSize;

    public OutboxPublisher(OutboxRepository repo, KafkaTemplate<String, String> kafka,
                           @Value("${drawback.outbox.batch-size:200}") int batchSize) {
        this.repo = repo;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${drawback.outbox.poll-ms:500}")
    @Transactional
    public int publishPending() {
        List<OutboxEvent> batch = repo.lockNextBatch(batchSize);
        if (batch.isEmpty()) return 0;

        List<CompletableFuture<?>> sends = batch.stream()
                .<CompletableFuture<?>>map(e -> kafka.send(e.getTopic(), e.getEventKey(), e.getPayload()))
                .toList();
        try {
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(15, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // inspect each future below: some may have succeeded
        }

        Set<String> blockedKeys = new HashSet<>();
        int published = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            CompletableFuture<?> f = sends.get(i);
            boolean ok = f.isDone() && !f.isCompletedExceptionally() && !f.isCancelled();
            if (ok && !blockedKeys.contains(event.getEventKey())) {
                event.markPublished();
                published++;
            } else {
                blockedKeys.add(event.getEventKey());
            }
        }
        if (published < batch.size()) {
            log.warn("Outbox: published {}/{}; remainder will be retried", published, batch.size());
        }
        return published;
    }

    @Scheduled(cron = "${drawback.outbox.cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void cleanup() {
        int n = repo.deletePublishedBefore(Instant.now().minus(Duration.ofDays(7)));
        log.info("Outbox cleanup removed {} rows", n);
    }
}
