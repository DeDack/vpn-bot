package com.abcvpn.entity;

import java.time.Instant;
import com.abcvpn.enumeration.SyncStatus;

public record Subscription(long telegramId, long tariffId, Instant expiresAt, boolean revoked,
                           String remnawaveUserId, String subscriptionUrl, SyncStatus syncStatus,
                           int syncRetryCount, Instant syncNextAt, String syncError,
                           Instant createdAt, Instant updatedAt) {
    public boolean active(Instant now) {
        return !revoked && (expiresAt == null || expiresAt.isAfter(now));
    }
}
