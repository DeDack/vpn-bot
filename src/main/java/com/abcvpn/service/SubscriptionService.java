package com.abcvpn.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import com.abcvpn.entity.Subscription;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.SubscriptionRepository;

@Service
public class SubscriptionService {
    private final SubscriptionRepository subscriptionRepository;
    private final Clock clock;
    public SubscriptionService(SubscriptionRepository subscriptionRepository, Clock clock) {
        this.subscriptionRepository = subscriptionRepository; this.clock = clock;
    }

    public Subscription find(long telegramId) { return subscriptionRepository.findByTelegramId(telegramId).orElse(null); }
    public Subscription requireActive(long telegramId) {
        Subscription subscription = subscriptionRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        if (!subscription.active(clock.instant())) throw new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        return subscription;
    }
    public String requireConnectionUrl(long telegramId) {
        Subscription subscription=requireActive(telegramId);
        if(subscription.subscriptionUrl()==null||subscription.subscriptionUrl().isBlank())
            throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,"VPN-доступ подготавливается. Попробуйте через минуту.");
        return subscription.subscriptionUrl();
    }
    public String requireLegacyConnectionUrl(String subscriptionUrl) {
        Subscription subscription=subscriptionRepository.findBySubscriptionUrl(subscriptionUrl)
                .orElseThrow(()->new FlowException(ErrorCode.ACCESS_DENIED));
        if(!subscription.active(clock.instant())) throw new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        return subscriptionUrl;
    }
    public Instant extend(long telegramId, long tariffId, int days) {
        Instant now = clock.instant();
        Subscription current = subscriptionRepository.findByTelegramIdForUpdate(telegramId).orElse(null);
        Instant base = current == null || current.expiresAt() == null || current.expiresAt().isBefore(now) ? now : current.expiresAt();
        Instant expiresAt = base.plus(days, ChronoUnit.DAYS);
        subscriptionRepository.upsertAccess(telegramId, tariffId, expiresAt);
        return expiresAt;
    }
    public void grant(long telegramId,long tariffId,Integer days) {
        Instant expiresAt = days == null ? null : clock.instant().plus(days,ChronoUnit.DAYS);
        subscriptionRepository.upsertAccess(telegramId,tariffId,expiresAt);
    }
    public void revoke(long telegramId) { subscriptionRepository.revoke(telegramId); }
}
