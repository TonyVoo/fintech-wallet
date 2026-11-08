package com.fintech.wallet.account.api.internal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record OperationRequest(
        @NotBlank @Size(max = 100) String operationId,
        @NotNull @DecimalMin(value = "0.0001", message = "must be greater than 0") BigDecimal amount) {
}
