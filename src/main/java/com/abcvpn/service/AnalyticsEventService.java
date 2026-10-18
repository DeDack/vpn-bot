package com.abcvpn.service;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import com.abcvpn.repository.AnalyticsEventRepository;

@Service
public class AnalyticsEventService {
    public static final String BOT_START="bot_start";
    public static final String TRIAL_AUTO_ACTIVATED="trial_auto_activated";
    public static final String CONNECTION_SCREEN_OPENED="connection_screen_opened";
    public static final String ONE_CLICK_REDIRECT_OPENED="one_click_redirect_opened";
    public static final String PLATFORM_SELECTED="platform_selected";
    public static final String SUBSCRIPTION_LINK_REQUESTED="subscription_link_requested";
    public static final String VPN_CONNECTED="vpn_connected";
    private final AnalyticsEventRepository repository;private final Clock clock;
    public AnalyticsEventService(AnalyticsEventRepository repository,Clock clock) {this.repository=repository;this.clock=clock;}
    public boolean record(long telegramId,String event) {return repository.record(telegramId,event,clock.instant());}
    public boolean record(long telegramId,String event,Instant occurredAt) {return repository.record(telegramId,event,occurredAt);}
}
