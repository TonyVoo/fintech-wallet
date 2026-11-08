package com.fintech.wallet.account.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.internal")
public record InternalApiProperties(String apiKey) {
}
