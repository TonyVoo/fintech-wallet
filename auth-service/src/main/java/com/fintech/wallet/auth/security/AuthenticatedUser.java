package com.fintech.wallet.auth.security;

import java.util.UUID;

/** Principal placed in the SecurityContext after a valid access token is presented. */
public record AuthenticatedUser(UUID id, String email, String role) {
}
