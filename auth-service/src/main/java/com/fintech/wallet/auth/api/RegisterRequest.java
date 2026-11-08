package com.fintech.wallet.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        // BCrypt only uses the first 72 bytes, so longer passwords are rejected rather than silently truncated
        @NotBlank @Size(min = 8, max = 72) String password) {
}
