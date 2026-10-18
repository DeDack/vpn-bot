package com.abcvpn.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.dto.RemnawaveWebhook;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.service.DeviceService;
import com.abcvpn.service.AnalyticsEventService;

@RestController
public class RemnawaveWebhookController {
    private final ObjectMapper objectMapper;private final DeviceService deviceService;private final AnalyticsEventService analytics;private final String secret;

    public RemnawaveWebhookController(ObjectMapper objectMapper,DeviceService deviceService,AnalyticsEventService analytics,AbcVpnProperties properties) {
        this.objectMapper=objectMapper;this.deviceService=deviceService;this.analytics=analytics;this.secret=properties.remnawave().webhookSecret();
    }

    @PostMapping("/remnawave/webhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestBody String body,@RequestHeader(value="X-Remnawave-Signature",required=false) String signature) {
        verify(body,signature);
        JsonNode root=readTree(body);
        if("user.first_connected".equals(root.path("event").asText())) {
            long telegramId=root.path("data").path("telegramId").asLong();
            if(telegramId>0) analytics.record(telegramId,AnalyticsEventService.VPN_CONNECTED,
                    java.time.Instant.parse(root.path("timestamp").asText()));
            return;
        }
        RemnawaveWebhook webhook=parse(body);
        if(!"user_hwid_devices.added".equals(webhook.event())||webhook.data()==null||webhook.data().user()==null||webhook.data().hwidUserDevice()==null) return;
        Long telegramId=webhook.data().user().telegramId();
        if(telegramId==null) throw new FlowException(ErrorCode.INVALID_REQUEST,"Webhook не содержит telegramId");
        var device=webhook.data().hwidUserDevice();
        deviceService.register(telegramId,device.hwid(),device.deviceModel(),device.platform(),device.osVersion());
    }
    private JsonNode readTree(String body) {
        try {return objectMapper.readTree(body);}
        catch(JsonProcessingException exception) {throw new FlowException(ErrorCode.INVALID_REQUEST,"Некорректный webhook Remnawave",exception);}
    }

    private RemnawaveWebhook parse(String body) {
        try { return objectMapper.readValue(body,RemnawaveWebhook.class); }
        catch(JsonProcessingException exception) { throw new FlowException(ErrorCode.INVALID_REQUEST,"Некорректный webhook Remnawave",exception); }
    }
    private void verify(String body,String signature) {
        if(secret==null||secret.isBlank()) throw new FlowException(ErrorCode.ACCESS_DENIED,"Webhook Remnawave не настроен");
        try {
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String expected=java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            if(signature==null||!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII)))
                throw new FlowException(ErrorCode.ACCESS_DENIED,"Некорректная подпись webhook");
        } catch(FlowException exception) { throw exception; }
        catch(Exception exception) { throw new FlowException(ErrorCode.ACCESS_DENIED,"Не удалось проверить webhook",exception); }
    }
}
