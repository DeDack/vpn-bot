package com.abcvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "abcvpn.platega")
public record PlategaProperties(boolean enabled, String baseUrl, String merchantId, String secret) {
}
