package com.abcvpn.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import com.abcvpn.repository.BroadcastRepository;
import com.abcvpn.repository.MarketingPreferenceRepository;
import com.abcvpn.telegram.TelegramClient;

@Service
public class BroadcastDeliveryService {
    private static final Logger log=LoggerFactory.getLogger(BroadcastDeliveryService.class);
    private static final ObjectMapper JSON=new ObjectMapper();
    private final BroadcastRepository repository;private final MarketingPreferenceRepository preferences;
    private final BroadcastService broadcastService;private final TelegramClient telegramClient;private final Clock clock;
    public BroadcastDeliveryService(BroadcastRepository repository,MarketingPreferenceRepository preferences,
                                    BroadcastService broadcastService,TelegramClient telegramClient,Clock clock) {
        this.repository=repository;this.preferences=preferences;this.broadcastService=broadcastService;this.telegramClient=telegramClient;this.clock=clock;
    }
    @Scheduled(fixedDelay=1000)
    public void deliver() {
        for(var delivery:repository.claim(20)) deliverOne(delivery);
    }
    public void deliverOne(BroadcastRepository.Delivery delivery) {
        if(!delivery.eligible()) {repository.markFailed(delivery.id(),"Marketing disabled",true);return;}
        if(delivery.recovered())
            log.warn("[TELEGRAM] Re-sending broadcast {} delivery {} after stale SENDING recovery",delivery.broadcastId(),delivery.id());
        try {
            Integer messageId=telegramClient.send(delivery.telegramId(),delivery.text(),broadcastService.keyboard(delivery));
            if(messageId==null) throw new IllegalStateException("Telegram delivery disabled");
            repository.markSent(delivery.id(),messageId);
        } catch(HttpStatusCodeException exception) {
            if(exception.getStatusCode().value()==403) {
                preferences.blocked(delivery.telegramId());repository.markFailed(delivery.id(),"Bot blocked by user",true);return;
            }
            retry(delivery,retryAfter(exception),"Telegram HTTP "+exception.getStatusCode().value());
        } catch(RuntimeException exception) {
            retry(delivery,null,exception.getMessage());
        }
    }
    private void retry(BroadcastRepository.Delivery delivery,Duration retryAfter,String error) {
        if(delivery.attempts()>=5) {repository.markFailed(delivery.id(),error,false);return;}
        Duration delay=retryAfter==null?Duration.ofSeconds(Math.min(300,5L*(1L<<Math.min(delivery.attempts(),6)))):retryAfter;
        repository.markRetry(delivery.id(),clock.instant().plus(delay),error);
        log.warn("[TELEGRAM] Broadcast {} delivery retry {}",delivery.broadcastId(),delivery.attempts());
    }
    private Duration retryAfter(HttpStatusCodeException exception) {
        if(exception.getStatusCode().value()!=429) return null;
        String header=exception.getResponseHeaders()==null?null:exception.getResponseHeaders().getFirst("Retry-After");
        Duration headerDelay=parseSeconds(header);
        if(headerDelay!=null) return headerDelay;
        try {
            JsonNode retryAfter=JSON.readTree(exception.getResponseBodyAsByteArray()).path("parameters").path("retry_after");
            return retryAfter.canConvertToLong()?positiveSeconds(retryAfter.longValue()):null;
        } catch(Exception ignored) {return null;}
    }
    private Duration parseSeconds(String value) {
        try {return value==null?null:positiveSeconds(Long.parseLong(value));}
        catch(NumberFormatException ignored) {return null;}
    }
    private Duration positiveSeconds(long seconds) {return seconds>=0?Duration.ofSeconds(seconds):null;}
}
