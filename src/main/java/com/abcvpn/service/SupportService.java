package com.abcvpn.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.entity.User;
import com.abcvpn.repository.SupportMessageRepository;
import com.abcvpn.telegram.TelegramClient;

@Service
public class SupportService {
    private static final Logger log=LoggerFactory.getLogger(SupportService.class);
    private static final DateTimeFormatter TIME_FORMAT=DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'МСК'")
            .withZone(ZoneId.of("Europe/Moscow"));
    private final SupportMessageRepository repository;
    private final TelegramClient client;
    private final long adminId;

    public SupportService(SupportMessageRepository repository,TelegramClient client,AbcVpnProperties properties) {
        this.repository=repository;this.client=client;this.adminId=properties.telegram().adminId();
    }

    public boolean isAdmin(long telegramId) { return adminId>0&&telegramId==adminId; }

    public void forward(long updateId,JsonNode message,User user) {
        int userMessageId=message.path("message_id").asInt();
        if(adminId<=0||!repository.claim(updateId,user.telegramId(),userMessageId,"USER_TO_ADMIN")) return;
        try {
            Integer headerId=client.send(adminId,header(message,user),null);
            Integer copiedId=client.copyMessage(adminId,user.telegramId(),userMessageId,headerId);
            if(copiedId==null) throw new IllegalStateException("Telegram не вернул message_id");
            repository.attachAdminMessage(updateId,copiedId);
            client.send(user.telegramId(),"💬 Сообщение отправлено в поддержку. Ответим здесь, в чате",null);
        } catch(RuntimeException exception) {
            repository.release(updateId);
            log.warn("[TELEGRAM] Support message {} forwarding failed",updateId);
            client.sendSilently(user.telegramId(),"Не удалось отправить сообщение в поддержку. Попробуйте ещё раз позже.",null);
        }
    }

    public void reply(long updateId,JsonNode message) {
        int repliedMessageId=message.path("reply_to_message").path("message_id").asInt();
        var userId=repository.findUserByAdminMessage(repliedMessageId);
        if(userId.isEmpty()) {
            client.send(adminId,"Не удалось определить пользователя. Ответьте Reply на скопированное сообщение обращения.",null);
            return;
        }
        int adminMessageId=message.path("message_id").asInt();
        if(!repository.claim(updateId,userId.getAsLong(),adminMessageId,"ADMIN_TO_USER")) return;
        try {
            client.copyMessage(userId.getAsLong(),adminId,adminMessageId,null);
            client.send(adminId,"✅ Ответ доставлен пользователю "+userId.getAsLong(),null);
        } catch(RuntimeException exception) {
            repository.release(updateId);
            log.warn("[TELEGRAM] Support reply {} delivery failed",updateId);
            client.send(adminId,"❌ Не удалось доставить ответ: "+reason(exception),null);
        }
    }

    private String header(JsonNode message,User user) {
        String time=TIME_FORMAT.format(Instant.ofEpochSecond(message.path("date").asLong()));
        String username=user.username()==null?"—":"@"+escape(user.username());
        String name=user.firstName()==null?"—":escape(user.firstName());
        return "💬 <b>Обращение в поддержку</b>\n\nID: <code>"+user.telegramId()+"</code>\nUsername: "+username+
                "\nИмя: "+name+"\nВремя: "+time+"\n\nОтветьте Reply на сообщение ниже.";
    }

    private String reason(RuntimeException exception) {
        if(exception instanceof HttpStatusCodeException status) return "Telegram API, HTTP "+status.getStatusCode().value();
        return "ошибка Telegram API";
    }
    private String escape(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
}
