package com.abcvpn.entity;

import java.time.Instant;

public record Broadcast(long id,String templateType,String messageText,String buttonText,String buttonType,
                        String buttonValue,boolean marketing,String status,int recipientCount,int sentCount,
                        int failedCount,Instant createdAt,Instant confirmedAt,Instant completedAt) {}
