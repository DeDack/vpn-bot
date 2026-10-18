package com.abcvpn.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import com.abcvpn.entity.Subscription;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.enumeration.SyncStatus;
import com.abcvpn.remnawave.RemnawaveSubscriptionClient;
import com.abcvpn.service.DeviceService;

class SubscriptionProxyControllerTest {
    @ParameterizedTest
    @ValueSource(strings={"Happ/4.6.0/Android","Happ/4.6.0/iOS"})
    void blockedHappDeviceGetsCompatibleLimitResponse(String userAgent) {
        DeviceService devices=mock(DeviceService.class);RemnawaveSubscriptionClient client=mock(RemnawaveSubscriptionClient.class);
        Subscription subscription=new Subscription(10,2,null,false,"1","url",SyncStatus.SYNCED,0,null,null,null,null);
        when(devices.access("token12345","device-hwid-123",null,"Android",null))
                .thenReturn(new DeviceService.AccessDecision(subscription,DeviceService.AccessStatus.BLOCKED));
        HttpHeaders headers=new HttpHeaders();headers.set(HttpHeaders.USER_AGENT,userAgent);headers.set("x-hwid","device-hwid-123");headers.set("x-device-os","Android");
        MockHttpServletRequest request=new MockHttpServletRequest();request.setServerName("sub.abc-vpn.example.com");

        var response=new SubscriptionProxyController(devices,client,mock(com.abcvpn.service.AnalyticsEventService.class),props()).subscription("token12345",headers,request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("x-hwid-max-devices-reached")).isEqualTo("true");
        assertThat(new String(response.getBody())).doesNotContain("147.45.");
    }

    private AbcVpnProperties props() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"abc_vpn_bot",false),
                new AbcVpnProperties.Remnawave("","","",""),"","","","","","","","password-123","session-secret-123",1,3);
    }
}
