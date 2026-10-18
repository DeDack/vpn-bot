package com.abcvpn.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import com.abcvpn.enumeration.PaymentStatus;

public record Payment(UUID id, long telegramId, long tariffId, UUID plategaTransactionId,
                      BigDecimal amountRub, BigDecimal paidAmountRub, long walletReservedKopecks, PaymentStatus status,
                      String paymentUrl, Instant paymentExpiresAt, boolean creationUncertain,
                      String termsVersion, String privacyVersion, Instant acceptedAt,
                      Instant createdAt, Instant paidAt) {}
