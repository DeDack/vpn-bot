package com.abcvpn.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.abcvpn.remnawave.RemnawaveClient;

@Service
public class AnalyticsSyncService {
    private static final Logger log=LoggerFactory.getLogger(AnalyticsSyncService.class);
    private final RemnawaveClient remnawaveClient;private final AnalyticsEventService analytics;
    public AnalyticsSyncService(RemnawaveClient remnawaveClient,AnalyticsEventService analytics) {
        this.remnawaveClient=remnawaveClient;this.analytics=analytics;
    }
    @Scheduled(initialDelay=30000,fixedDelay=900000)
    public void syncVpnConnections() {
        try {
            for(var user:remnawaveClient.connectedUsers())
                analytics.record(user.telegramId(),AnalyticsEventService.VPN_CONNECTED,user.firstConnectedAt());
        } catch(RuntimeException exception) {log.warn("[REMNAWAVE] VPN analytics sync failed");}
    }
}
