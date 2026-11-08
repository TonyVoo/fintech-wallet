package com.fintech.wallet.transaction.client;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.UUID;

/** Calls the internal API of account-service. Every call has a timeout. */
@Component
public class AccountClient {

    public record AccountInfo(UUID id, UUID ownerId, String currency) {
    }

    /** Definitive answers from account-service. Anything unclear is a {@link RemoteUnavailableException}. */
    public enum OperationResult {
        APPLIED,
        ACCOUNT_NOT_FOUND,
        INSUFFICIENT_FUNDS
    }

    private record OperationBody(String operationId, BigDecimal amount) {
    }

    private final RestClient client;

    public AccountClient(AccountsProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Api-Key", properties.apiKey())
                .requestFactory(factory)
                .build();
    }

    public Optional<AccountInfo> getAccount(UUID id) {
        try {
            return client.get().uri("/internal/accounts/{id}", id).exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status == 200) {
                    return Optional.ofNullable(response.bodyTo(AccountInfo.class));
                }
                if (status == 404) {
                    return Optional.<AccountInfo>empty();
                }
                throw unexpected("lookup of account " + id, status);
            });
        } catch (RestClientException e) {
            throw new RemoteUnavailableException("account-service is unreachable", e);
        }
    }

    /** Idempotent: repeating the same operationId never debits twice. */
    public OperationResult debit(UUID accountId, String operationId, BigDecimal amount) {
        return operate(accountId, "debit", operationId, amount);
    }

    /** Idempotent: repeating the same operationId never credits twice. */
    public OperationResult credit(UUID accountId, String operationId, BigDecimal amount) {
        return operate(accountId, "credit", operationId, amount);
    }

    private OperationResult operate(UUID accountId, String action, String operationId, BigDecimal amount) {
        try {
            return client.post().uri("/internal/accounts/{id}/{action}", accountId, action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new OperationBody(operationId, amount))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        return switch (status) {
                            case 200 -> OperationResult.APPLIED; // APPLIED and ALREADY_APPLIED both mean "it is done"
                            case 404 -> OperationResult.ACCOUNT_NOT_FOUND;
                            case 422 -> OperationResult.INSUFFICIENT_FUNDS;
                            default -> throw unexpected(action + " of " + operationId, status);
                        };
                    });
        } catch (RestClientException e) {
            throw new RemoteUnavailableException("account-service is unreachable", e);
        }
    }

    private static RemoteUnavailableException unexpected(String what, int status) {
        return new RemoteUnavailableException(what + " returned HTTP " + status, null);
    }
}
