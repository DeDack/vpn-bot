package com.abcvpn.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.remnawave.RemnawaveSubscriptionClient;
import com.abcvpn.service.DeviceService;
import com.abcvpn.service.AnalyticsEventService;

@RestController
public class SubscriptionProxyController {
    private final DeviceService deviceService;private final RemnawaveSubscriptionClient subscriptionClient;
    private final AnalyticsEventService analytics;
    private final String botUsername;

    public SubscriptionProxyController(DeviceService deviceService,RemnawaveSubscriptionClient subscriptionClient,
                                       AnalyticsEventService analytics,AbcVpnProperties properties) {
        this.deviceService=deviceService;this.subscriptionClient=subscriptionClient;
        this.analytics=analytics;
        this.botUsername=properties.telegram().botUsername().replace("@","");
    }

    @GetMapping("/{token:[A-Za-z0-9_-]{8,64}}")
    public ResponseEntity<byte[]> subscription(@PathVariable String token,@RequestHeader HttpHeaders headers,HttpServletRequest request) {
        if(!"sub.abc-vpn.example.com".equalsIgnoreCase(request.getServerName())) throw new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        String hwid=headers.getFirst("x-hwid");
        if(hwid==null||!hwid.matches("^[a-zA-Z0-9=-]{10,64}$")) return blocked("Требуется идентификатор устройства");
        var access=deviceService.access(token,hwid,headers.getFirst("x-device-model"),headers.getFirst("x-device-os"),headers.getFirst("x-ver-os"));
        if(access.status()==DeviceService.AccessStatus.EXPIRED) return expired();
        if(access.status()==DeviceService.AccessStatus.BLOCKED) return blocked("Лимит устройств превышен");
        analytics.record(access.subscription().telegramId(),AnalyticsEventService.SUBSCRIPTION_LINK_REQUESTED);
        var response=subscriptionClient.get(token,headers,request.getRemoteAddr());
        return ResponseEntity.status(response.status()).headers(response.headers()).body(response.body());
    }

    private ResponseEntity<byte[]> blocked(String reason) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).header("x-hwid-active","true")
                .header("x-hwid-limit","true").header("x-hwid-max-devices-reached","true")
                .header("profile-title",title(reason)).body(message(reason,"Отключите другое устройство в боте"));
    }

    private ResponseEntity<byte[]> expired() {
        String reason="Подписка закончилась";
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
                .header("profile-title",title(reason)).body(message(reason,"Продлите подписку в боте"));
    }

    private String title(String value) {
        return "base64:"+Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] message(String reason,String action) {
        String body=placeholder(reason)+"\n"+placeholder(action)+"\n"+placeholder("Открыть Telegram: @"+botUsername);
        return Base64.getEncoder().encode(body.getBytes(StandardCharsets.UTF_8));
    }

    private String placeholder(String title) {
        return "vless://00000000-0000-0000-0000-000000000000@0.0.0.0:1?encryption=none&type=tcp&security=none#"+
                URLEncoder.encode(title,StandardCharsets.UTF_8).replace("+","%20");
    }
}
