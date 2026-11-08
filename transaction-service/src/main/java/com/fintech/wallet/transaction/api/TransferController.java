package com.fintech.wallet.transaction.api;

import com.fintech.wallet.transaction.service.TransferRejectedException;
import com.fintech.wallet.transaction.service.TransferResult;
import com.fintech.wallet.transaction.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /**
     * The Idempotency-Key header is mandatory: the client generates one key per intended transfer and re-sends the
     * same key when it retries, so a network glitch can never move the money twice.
     *
     * <ul>
     *   <li>201 created and completed</li>
     *   <li>200 the same request was already handled; this is its current state</li>
     *   <li>202 accepted but not finished (a downstream service did not answer); it will be completed automatically,
     *       poll GET /api/transfers/{id}</li>
     *   <li>422 refused (insufficient funds) or rolled back (destination unavailable); see failureReason</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw TransferRejectedException.invalid(
                    "Idempotency-Key header is required (1-64 characters: letters, digits, . _ : -)");
        }
        TransferResult result = service.transfer(userId(jwt), idempotencyKey, request.fromAccountId(),
                request.toAccountId(), request.amount(), request.description());
        TransferResponse body = TransferResponse.from(result.transfer());
        return ResponseEntity.status(statusFor(result)).body(body);
    }

    @GetMapping("/{id}")
    public TransferResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return TransferResponse.from(service.get(userId(jwt), id));
    }

    @GetMapping
    public List<TransferResponse> listMine(@AuthenticationPrincipal Jwt jwt) {
        return service.listMine(userId(jwt)).stream().map(TransferResponse::from).toList();
    }

    private static HttpStatus statusFor(TransferResult result) {
        return switch (result.transfer().getStatus()) {
            case COMPLETED -> result.created() ? HttpStatus.CREATED : HttpStatus.OK;
            case PENDING, DEBITED -> HttpStatus.ACCEPTED;
            case FAILED, REFUNDED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
