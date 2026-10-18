package com.abcvpn.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import com.abcvpn.config.AbcVpnProperties;

class PaymentPageControllerTest {
    @Test
    void failedPaymentRedirectsToTelegramBot() {
        var controller=new PaymentPageController(properties());

        var response=controller.failed();

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation().toString())
                .isEqualTo("https://t.me/abc_vpn_bot?start=payment_failed");
    }

    private AbcVpnProperties properties() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"abc_vpn_bot",false),
                new AbcVpnProperties.Remnawave("","","",""),"https://abc-vpn.example.com","https://api.abc-vpn.example.com",
                "@support","privacy","terms","1","1","password-123","session-secret-123",1,3);
    }
}
