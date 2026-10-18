package com.abcvpn.entity;

import java.time.Instant;
import com.abcvpn.enumeration.SourceType;

public record User(long telegramId, String username, String firstName, boolean trialUsed,
                   SourceType sourceType, Long campaignId, Long referrerTelegramId,
                   String referralCode, boolean banned, Instant createdAt, Instant lastActivityAt) {}
