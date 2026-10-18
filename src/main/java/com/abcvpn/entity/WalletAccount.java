package com.abcvpn.entity;

public record WalletAccount(long telegramId,long availableKopecks,long reservedKopecks,
                            long totalEarnedKopecks,long totalSpentKopecks) {}
