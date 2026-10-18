package com.abcvpn.entity;

import java.time.Instant;
import java.util.UUID;
import com.abcvpn.enumeration.WalletTransactionStatus;
import com.abcvpn.enumeration.WalletTransactionType;

public record WalletTransaction(long id,long telegramId,long amountKopecks,String direction,
                                WalletTransactionType type,WalletTransactionStatus status,
                                String referenceKey,UUID paymentId,Long referredTelegramId,
                                String description,Instant createdAt,Instant completedAt) {}
