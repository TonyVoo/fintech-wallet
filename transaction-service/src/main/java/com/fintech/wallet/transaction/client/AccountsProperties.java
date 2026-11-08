package com.fintech.wallet.transaction.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.accounts")
public record AccountsProperties(String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
}
