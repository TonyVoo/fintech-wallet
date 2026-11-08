package com.fintech.wallet.account.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull @DecimalMin(value = "0.0001", message = "must be greater than 0") BigDecimal amount) {
}
