package com.fintech.wallet.auth.service;

public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds) {
}
