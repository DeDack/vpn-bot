package com.abcvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "abcvpn")
public record AbcVpnProperties(
        Telegram telegram,
        Remnawave remnawave,
        String publicBaseUrl,
        String apiBaseUrl,
        String supportUsername,
        String privacyPolicyUrl,
        String termsUrl,
        String privacyVersion,
        String termsVersion,
        String adminPassword,
        String adminSessionSecret,
        int referralTrialDays,
        int referralPaymentDays
) {
    public record Telegram(String token, long adminId, String botUsername, boolean enabled) {}
    public record Remnawave(String baseUrl, String subscriptionBaseUrl, String token, String webhookSecret) {}
}
