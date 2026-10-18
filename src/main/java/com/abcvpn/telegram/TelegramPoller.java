package com.abcvpn.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TelegramPoller {
    private static final Logger log=LoggerFactory.getLogger(TelegramPoller.class);
    private final TelegramClient client;
    private final TelegramHandler handler;
    private final AtomicBoolean polling=new AtomicBoolean();
    private final AtomicBoolean menuConfigured=new AtomicBoolean();
    private long offset;
    public TelegramPoller(TelegramClient client,TelegramHandler handler) { this.client=client;this.handler=handler; }

    @Scheduled(fixedDelay=1000)
    public void poll() {
        if(!client.enabled()||!polling.compareAndSet(false,true)) return;
        try {
            configureMenu();
            JsonNode response=client.getUpdates(offset);
            if(response==null||!response.path("ok").asBoolean()) return;
            for(JsonNode update:response.path("result")) {
                offset=Math.max(offset,update.path("update_id").asLong()+1);
                try { handler.handle(update); }
                catch(RuntimeException exception) {
                    log.warn("[TELEGRAM] Update {} failed",update.path("update_id").asLong(),exception);
                    client.notifyAdmin("[TELEGRAM] Ошибка обработки update "+update.path("update_id").asLong());
                }
            }
        } catch(RuntimeException exception) { log.warn("[TELEGRAM] Long polling failed"); }
        finally { polling.set(false); }
    }
    private void configureMenu() {
        if(menuConfigured.get()) return;
        client.configureMenu();
        menuConfigured.set(true);
        log.info("[TELEGRAM] Bot command menu configured");
    }
}
