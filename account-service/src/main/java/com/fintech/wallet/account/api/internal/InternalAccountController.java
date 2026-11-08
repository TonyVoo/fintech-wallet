package com.fintech.wallet.account.api.internal;

import com.fintech.wallet.account.domain.Account;
import com.fintech.wallet.account.domain.OperationType;
import com.fintech.wallet.account.service.AccountService;
import com.fintech.wallet.account.service.OperationOutcome;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Endpoints for other services of the system, not for end users. Protected by a service API key
 * (see SecurityConfig) and never to be exposed through the public gateway.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final AccountService service;

    public InternalAccountController(AccountService service) {
        this.service = service;
    }

    public record InternalAccountResponse(UUID id, UUID ownerId, String currency) {
    }

    public record OperationResponse(String status, BigDecimal balance) {
    }

    @GetMapping("/{id}")
    public InternalAccountResponse get(@PathVariable UUID id) {
        Account account = service.getForService(id);
        return new InternalAccountResponse(account.getId(), account.getOwnerId(), account.getCurrency());
    }

    @PostMapping("/{id}/debit")
    public OperationResponse debit(@PathVariable UUID id, @Valid @RequestBody OperationRequest request) {
        return toResponse(service.applyOperation(request.operationId(), id, OperationType.DEBIT, request.amount()));
    }

    @PostMapping("/{id}/credit")
    public OperationResponse credit(@PathVariable UUID id, @Valid @RequestBody OperationRequest request) {
        return toResponse(service.applyOperation(request.operationId(), id, OperationType.CREDIT, request.amount()));
    }

    private static OperationResponse toResponse(OperationOutcome outcome) {
        return new OperationResponse(outcome.status().name(), outcome.balance());
    }
}
