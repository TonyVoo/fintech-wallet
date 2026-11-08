package com.fintech.wallet.notification.api;

import com.fintech.wallet.notification.service.NotificationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** The caller's own notifications, newest first. */
    @GetMapping
    public List<NotificationResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        return service.listMine(UUID.fromString(jwt.getSubject())).stream().map(NotificationResponse::from).toList();
    }
}
