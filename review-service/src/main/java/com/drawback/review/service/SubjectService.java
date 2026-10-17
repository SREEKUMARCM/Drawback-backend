package com.drawback.review.service;

import com.drawback.events.SubjectEvent;
import com.drawback.events.Topics;
import com.drawback.review.domain.SubjectEntity;
import com.drawback.review.outbox.OutboxService;
import com.drawback.review.repo.SubjectRepository;
import com.drawback.review.web.Dtos.CreateSubjectRequest;
import com.drawback.review.web.Dtos.SubjectResponse;
import com.drawback.security.ApiException;
import com.drawback.security.AuthenticatedUser;
import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubjectService {

    private final SubjectRepository subjects;
    private final OutboxService outbox;

    public SubjectService(SubjectRepository subjects, OutboxService outbox) {
        this.subjects = subjects;
        this.outbox = outbox;
    }

    @Transactional
    public SubjectResponse create(CreateSubjectRequest req, AuthenticatedUser user) {
        if (!user.verified()) throw ApiException.forbidden("Verify your account before adding subjects");
        SubjectEntity s = subjects.save(new SubjectEntity(uniqueSlug(req.name()), req.name().trim(),
                req.category().trim().toLowerCase(Locale.ROOT), req.description(), user.userId()));
        SubjectEvent event = EventFactory.subject(SubjectEvent.Type.SUBJECT_CREATED, s);
        outbox.enqueue(Topics.SUBJECTS, s.getId().toString(), event.eventId(), event);
        return SubjectResponse.of(s);
    }

    String uniqueSlug(String name) {
        String base = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.length() > 60) base = base.substring(0, 60);
        if (base.isEmpty()) base = "subject";
        return base + "-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
