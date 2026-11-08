package com.fintech.wallet.transaction.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.recovery")
public record RecoveryProperties(boolean enabled, Duration staleAfter, int batchSize) {
}
