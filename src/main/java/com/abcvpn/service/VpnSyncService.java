package com.abcvpn.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.abcvpn.entity.Subscription;
import com.abcvpn.repository.SubscriptionRepository;

@Service
public class VpnSyncService {
    private static final Logger log=LoggerFactory.getLogger(VpnSyncService.class);
    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessService vpnAccessService;
    private final Clock clock;
    public VpnSyncService(SubscriptionRepository subscriptionRepository,VpnAccessService vpnAccessService,Clock clock) {
        this.subscriptionRepository=subscriptionRepository;this.vpnAccessService=vpnAccessService;this.clock=clock;
    }

    @Scheduled(fixedDelayString="${abcvpn.sync-delay-ms:10000}")
    public void syncDue() {
        for(Subscription subscription:subscriptionRepository.claimDueForSync(clock.instant(),100)) syncOne(subscription);
    }
    @Scheduled(fixedDelayString="${abcvpn.expiry-delay-ms:60000}")
    public void queueExpired() {
        for(Subscription subscription:subscriptionRepository.findExpiredNotQueued(clock.instant(),500))
            subscriptionRepository.markPending(subscription.telegramId());
    }
    @Scheduled(fixedDelayString="${abcvpn.reconciliation-delay-ms:900000}")
    public void reconcile() {
        for(Subscription subscription:subscriptionRepository.findProvisioned(500)) {
            try { if(vpnAccessService.differs(subscription)) subscriptionRepository.markPending(subscription.telegramId()); }
            catch(RuntimeException exception) { log.warn("[REMNAWAVE] Reconciliation failed for user {}",subscription.telegramId()); }
        }
    }
    public void syncOne(Subscription subscription) {
        try { vpnAccessService.sync(subscription); }
        catch(RuntimeException exception) {
            int retry=subscription.syncRetryCount()+1;
            subscriptionRepository.markError(subscription.telegramId(),retry,clock.instant().plus(backoff(retry)),exception.getMessage());
            log.warn("[REMNAWAVE] Sync failed for user {}, retry {}",subscription.telegramId(),retry);
        }
    }
    private Duration backoff(int retry) {
        return switch(retry) { case 1->Duration.ofSeconds(10);case 2->Duration.ofSeconds(30);case 3->Duration.ofMinutes(1);case 4->Duration.ofMinutes(5);default->Duration.ofMinutes(15);};
    }
}
