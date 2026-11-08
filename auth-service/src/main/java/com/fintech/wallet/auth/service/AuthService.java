package com.fintech.wallet.auth.service;

import com.fintech.wallet.auth.domain.RefreshToken;
import com.fintech.wallet.auth.domain.Role;
import com.fintech.wallet.auth.domain.User;
import com.fintech.wallet.auth.repository.RefreshTokenRepository;
import com.fintech.wallet.auth.repository.UserRepository;
import com.fintech.wallet.auth.security.JwtProperties;
import com.fintech.wallet.auth.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JwtProperties properties;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService, JwtProperties properties) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Transactional
    public User register(String email, String rawPassword) {
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmail(normalized)) {
            throw new EmailAlreadyUsedException();
        }
        try {
            return users.saveAndFlush(new User(normalized, passwordEncoder.encode(rawPassword), Role.USER));
        } catch (DataIntegrityViolationException e) {
            // two concurrent registrations with the same email: the unique constraint is the real guard
            throw new EmailAlreadyUsedException();
        }
    }

    /** Throws BadCredentialsException (-> 401) on a wrong email or password. */
    @Transactional
    public TokenPair login(String email, String rawPassword) {
        String normalized = email.trim().toLowerCase();
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(normalized, rawPassword));
        User user = users.findByEmail(normalized).orElseThrow(InvalidRefreshTokenException::new);
        return issueTokens(user);
    }

    /**
     * Refresh token rotation: each refresh token works exactly once. Presenting an already-used token
     * means it was probably stolen, so every session of that user is revoked.
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(hash(rawRefreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);
        if (stored.isRevoked()) {
            refreshTokens.revokeAllForUser(stored.getUserId());
            throw new InvalidRefreshTokenException();
        }
        if (stored.isExpired()) {
            throw new InvalidRefreshTokenException();
        }
        stored.revoke();
        User user = users.findById(stored.getUserId()).orElseThrow(InvalidRefreshTokenException::new);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(hash(rawRefreshToken)).ifPresent(RefreshToken::revoke);
    }

    @Transactional(readOnly = true)
    public User getUser(UUID id) {
        return users.findById(id).orElseThrow(InvalidRefreshTokenException::new);
    }

    private TokenPair issueTokens(User user) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawRefresh = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshTokens.save(new RefreshToken(user.getId(), hash(rawRefresh),
                Instant.now().plus(properties.refreshTokenTtl())));
        return new TokenPair(jwtService.generateAccessToken(user), rawRefresh, jwtService.accessTokenTtlSeconds());
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
