package com.abcvpn.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentLink(UUID paymentId, UUID transactionId, String url, Instant expiresAt, boolean reused) {}
