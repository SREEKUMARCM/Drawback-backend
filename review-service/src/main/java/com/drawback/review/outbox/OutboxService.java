package com.drawback.review.outbox;

import com.drawback.review.domain.OutboxEvent;
import com.drawback.review.repo.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends events to the outbox. MANDATORY propagation: it can only be called inside the business transaction. */
@Service
public class OutboxService {

    private final OutboxRepository repo;
    private final ObjectMapper mapper;

    public OutboxService(OutboxRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String topic, String key, UUID eventId, Object event) {
        try {
            repo.save(new OutboxEvent(eventId, topic, key, mapper.writeValueAsString(event)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + event.getClass().getSimpleName(), e);
        }
    }
}
