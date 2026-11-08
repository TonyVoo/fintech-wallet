package com.fintech.wallet.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full stack against a real PostgreSQL: security, Flyway, JPA, BCrypt, JWT, refresh-token rotation. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthApiIT {

    private static final String PASSWORD = "password123";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void registerLoginAndReadOwnProfile() throws Exception {
        String email = uniqueEmail();

        register(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        String access = JsonPath.read(login(email, PASSWORD).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void passwordIsStoredAsABcryptHash() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        String stored = jdbc.queryForObject("select password_hash from users where email = ?", String.class, email);

        assertThat(stored).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void emailsAreCaseInsensitiveAndUnique() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        register(email.toUpperCase(), PASSWORD).andExpect(status().isConflict());
        login(email.toUpperCase(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void invalidRegistrationInputIsRejected() throws Exception {
        register("not-an-email", PASSWORD).andExpect(status().isBadRequest());
        register(uniqueEmail(), "short").andExpect(status().isBadRequest());
        register(uniqueEmail(), "x".repeat(73)).andExpect(status().isBadRequest());
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        String wrongPassword = login(email, "wrong-password").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<String>read(wrongPassword, "$.detail"))
                .isEqualTo(JsonPath.<String>read(unknownUser, "$.detail"));
    }

    @Test
    void protectedEndpointNeedsAValidToken() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshTokenIsRotatedAndOnlyItsHashIsStored() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        String first = refreshTokenOf(login(email, PASSWORD));

        String second = refreshTokenOf(refresh(first).andExpect(status().isOk()));

        assertThat(second).isNotEqualTo(first);
        Integer rawMatches = jdbc.queryForObject(
                "select count(*) from refresh_tokens where token_hash in (?, ?)", Integer.class, first, second);
        assertThat(rawMatches).isZero();
    }

    @Test
    void reusingAConsumedRefreshTokenRevokesEveryActiveSession() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        String stolen = refreshTokenOf(login(email, PASSWORD));
        String legitimateNext = refreshTokenOf(refresh(stolen).andExpect(status().isOk()));

        // the attacker replays the old token: refused ...
        refresh(stolen).andExpect(status().isUnauthorized());
        // ... and the token the real user just received is dead too
        refresh(legitimateNext).andExpect(status().isUnauthorized());

        // the user can still sign in again with their password
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void loggedOutRefreshTokenCannotBeUsed() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());
        String refresh = refreshTokenOf(login(email, PASSWORD));

        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isNoContent());

        refresh(refresh).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownRefreshTokenIsRejected() throws Exception {
        refresh("this-token-was-never-issued").andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, password)));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, password)));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"));
    }

    private static String refreshTokenOf(ResultActions response) throws Exception {
        return JsonPath.read(response.andReturn().getResponse().getContentAsString(), "$.refreshToken");
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }
}
