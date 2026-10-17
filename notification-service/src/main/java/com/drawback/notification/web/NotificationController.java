package com.drawback.notification.web;

import com.drawback.notification.domain.Notification;
import com.drawback.notification.service.NotificationService;
import com.drawback.security.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A user can only ever see and modify their own notifications: the user id comes from the JWT, never from the URL. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record NotificationResponse(UUID id, String type, String title, String message, String link,
                                       Instant createdAt, boolean read) {
        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getLink(),
                    n.getCreatedAt(), n.getReadAt() != null);
        }
    }

    public record PageResponse(List<NotificationResponse> items, int page, int size, long total, long unread) {}

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse list(@AuthenticationPrincipal AuthenticatedUser user,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var p = service.list(user.userId(), page, size);
        return new PageResponse(p.map(NotificationResponse::of).getContent(), p.getNumber(), p.getSize(),
                p.getTotalElements(), service.unreadCount(user.userId()));
    }

    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        service.markRead(id, user.userId());
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll(@AuthenticationPrincipal AuthenticatedUser user) {
        return Map.of("updated", service.markAllRead(user.userId()));
    }
}
