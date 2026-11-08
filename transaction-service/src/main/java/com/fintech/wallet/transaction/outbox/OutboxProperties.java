package com.fintech.wallet.transaction.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(boolean enabled, String topic, int batchSize, Duration sendTimeout) {
}
