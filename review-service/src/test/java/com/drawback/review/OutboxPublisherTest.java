package com.drawback.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.drawback.review.domain.OutboxEvent;
import com.drawback.review.outbox.OutboxPublisher;
import com.drawback.review.repo.OutboxRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings("unchecked")
class OutboxPublisherTest {

    final OutboxRepository repo = mock(OutboxRepository.class);
    final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    final OutboxPublisher publisher = new OutboxPublisher(repo, kafka, 100);

    private static OutboxEvent event(String key, String payload) {
        return new OutboxEvent(UUID.randomUUID(), "topic", key, payload);
    }

    @Test
    void marksEveryAcknowledgedEventPublished() {
        var a = event("k1", "p1");
        var b = event("k2", "p2");
        when(repo.lockNextBatch(100)).thenReturn(List.of(a, b));
        when(kafka.send(eq("topic"), eq("k1"), eq("p1"))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafka.send(eq("topic"), eq("k2"), eq("p2"))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishPending()).isEqualTo(2);
        assertThat(a.getPublishedAt()).isNotNull();
        assertThat(b.getPublishedAt()).isNotNull();
    }

    @Test
    void failedSendStaysPendingAndBlocksLaterEventsOfTheSameKeyToPreserveOrdering() {
        var first = event("k1", "p1");
        var secondSameKey = event("k1", "p2");
        var otherKey = event("k2", "p3");
        when(repo.lockNextBatch(100)).thenReturn(List.of(first, secondSameKey, otherKey));
        when(kafka.send(eq("topic"), eq("k1"), eq("p1"))).thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(kafka.send(eq("topic"), eq("k1"), eq("p2"))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(kafka.send(eq("topic"), eq("k2"), eq("p3"))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishPending()).isEqualTo(1);
        assertThat(first.getPublishedAt()).isNull();
        assertThat(secondSameKey.getPublishedAt()).as("must not overtake the failed event with the same key").isNull();
        assertThat(otherKey.getPublishedAt()).isNotNull();
    }

    @Test
    void emptyOutboxDoesNothing() {
        when(repo.lockNextBatch(100)).thenReturn(List.of());
        assertThat(publisher.publishPending()).isZero();
    }
}
