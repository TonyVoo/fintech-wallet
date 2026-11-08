package com.fintech.wallet.auth.security;

import com.fintech.wallet.auth.domain.Role;
import com.fintech.wallet.auth.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    // 48 bytes, base64
    private static final String SECRET = "ZGV2LW9ubHktc2VjcmV0LXBsZWFzZS1jaGFuZ2UtaW4tcHJvZC0wMTIzNDU2Nzg5";
    private static final String OTHER_SECRET = "b3RoZXItc2VjcmV0LWtleS1mb3ItdGVzdGluZy1vbmx5LTAxMjM0NTY3ODkwMTIz";

    private JwtService service(String secret, Duration ttl) {
        return new JwtService(new JwtProperties(secret, ttl, Duration.ofDays(7)));
    }

    private final User user = new User("a@example.com", "hash", Role.USER);

    @Test
    void roundTripReturnsSameUser() {
        JwtService service = service(SECRET, Duration.ofMinutes(15));
        String token = service.generateAccessToken(user);

        var parsed = service.parse(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().id()).isEqualTo(user.getId());
        assertThat(parsed.get().email()).isEqualTo("a@example.com");
        assertThat(parsed.get().role()).isEqualTo("USER");
    }

    @Test
    void expiredTokenIsRejected() {
        JwtService service = service(SECRET, Duration.ofSeconds(-5));
        assertThat(service.parse(service.generateAccessToken(user))).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = service(OTHER_SECRET, Duration.ofMinutes(15)).generateAccessToken(user);
        assertThat(service(SECRET, Duration.ofMinutes(15)).parse(token)).isEmpty();
    }

    @Test
    void tamperedAndGarbageTokensAreRejected() {
        JwtService service = service(SECRET, Duration.ofMinutes(15));
        String token = service.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThat(service.parse(tampered)).isEmpty();
        assertThat(service.parse("not-a-jwt")).isEmpty();
        assertThat(service.parse("")).isEmpty();
    }
}
