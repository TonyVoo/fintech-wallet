package com.fintech.wallet.auth.api;

import com.fintech.wallet.auth.domain.User;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, String email, String role, Instant createdAt) {

    public static UserResponse from(User u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getRole().name(), u.getCreatedAt());
    }
}
