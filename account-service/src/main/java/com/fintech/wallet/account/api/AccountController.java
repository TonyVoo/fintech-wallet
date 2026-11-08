package com.fintech.wallet.account.api;

import com.fintech.wallet.account.service.AccountService;
import com.fintech.wallet.account.service.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateAccountRequest request) {
        return AccountResponse.from(service.create(currentUser(jwt), request.ownerName(), request.currency()));
    }

    @GetMapping
    public List<AccountResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        return service.listMine(currentUser(jwt)).stream().map(AccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return AccountResponse.from(service.get(currentUser(jwt), id));
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                   @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(service.deposit(currentUser(jwt), id, request.amount()));
    }

    @PostMapping("/{id}/withdraw")
    public AccountResponse withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                    @Valid @RequestBody AmountRequest request) {
        return AccountResponse.from(service.withdraw(currentUser(jwt), id, request.amount()));
    }

    private static CurrentUser currentUser(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), "ADMIN".equals(jwt.getClaimAsString("role")));
    }
}
