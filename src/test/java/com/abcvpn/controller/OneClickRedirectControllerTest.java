package com.abcvpn.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.exception.FlowException;
import com.abcvpn.service.AnalyticsEventService;
import com.abcvpn.service.OneClickSignature;
import com.abcvpn.service.SubscriptionService;

class OneClickRedirectControllerTest {
    private static final Instant NOW=Instant.parse("2026-10-11T10:00:00Z");

    @Test
    void redirectsSignedLinkAndRecordsClick() {
        SubscriptionService subscriptions=mock(SubscriptionService.class);AnalyticsEventService analytics=mock(AnalyticsEventService.class);
        when(subscriptions.requireConnectionUrl(10)).thenReturn("https://sub.abc-vpn.example.com/test");
        long expires=NOW.plusSeconds(3600).getEpochSecond();

        var response=controller(subscriptions,analytics).redirect(10L,expires,
                OneClickSignature.create(10,expires,"session-secret-123"),null);

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("happ://add/https://sub.abc-vpn.example.com/test");
        verify(analytics).record(10,AnalyticsEventService.ONE_CLICK_REDIRECT_OPENED);
    }

    @Test
    void rejectsForgedUserId() {
        long expires=NOW.plusSeconds(3600).getEpochSecond();
        assertThatThrownBy(()->controller(mock(SubscriptionService.class),mock(AnalyticsEventService.class))
                .redirect(11L,expires,OneClickSignature.create(10,expires,"session-secret-123"),null))
                .isInstanceOf(FlowException.class);
    }

    @Test
    void expiredSignedLinkReturnsUserToBotForFreshButton() {
        long expires=NOW.minusSeconds(1).getEpochSecond();
        var response=controller(mock(SubscriptionService.class),mock(AnalyticsEventService.class))
                .redirect(10L,expires,OneClickSignature.create(10,expires,"session-secret-123"),null);
        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("https://t.me/abc_vpn_bot?start=connect");
    }

    @Test
    void acceptsKnownLegacyHappLinkOnTrustedSubscriptionDomain() {
        SubscriptionService subscriptions=mock(SubscriptionService.class);
        when(subscriptions.requireLegacyConnectionUrl("https://sub.abc-vpn.example.com/known-token"))
                .thenReturn("https://sub.abc-vpn.example.com/known-token");

        var response=controller(subscriptions,mock(AnalyticsEventService.class))
                .redirect(null,null,null,"happ://add/https://sub.abc-vpn.example.com/known-token");

        assertThat(response.getHeaders().getLocation().toString()).isEqualTo("happ://add/https://sub.abc-vpn.example.com/known-token");
    }

    @Test
    void rejectsLegacyExternalAndNestedRedirects() {
        var controller=controller(mock(SubscriptionService.class),mock(AnalyticsEventService.class));
        assertThatThrownBy(()->controller.redirect(null,null,null,"happ://add/https://evil.example/key")).isInstanceOf(FlowException.class);
        assertThatThrownBy(()->controller.redirect(null,null,null,"happ://add/https://sub.abc-vpn.example.com/key?next=https://evil.example"))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(()->controller.redirect(null,null,null,"https://sub.abc-vpn.example.com/key")).isInstanceOf(FlowException.class);
    }

    @Test
    void rejectsDoubleEncodedMalformedAndMixedLegacyParameters() {
        var controller=controller(mock(SubscriptionService.class),mock(AnalyticsEventService.class));
        assertThatThrownBy(()->controller.redirect(null,null,null,"happ%3A%2F%2Fadd%2Fhttps%3A%2F%2Fsub.abc-vpn.example.com%2Fkey"))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(()->controller.redirect(10L,null,null,"happ://add/https://sub.abc-vpn.example.com/key"))
                .isInstanceOf(FlowException.class);
    }

    private OneClickRedirectController controller(SubscriptionService subscriptions,AnalyticsEventService analytics) {
        return new OneClickRedirectController(subscriptions,analytics,properties(),Clock.fixed(NOW,ZoneOffset.UTC));
    }

    private AbcVpnProperties properties() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"abc_vpn_bot",false),
                new AbcVpnProperties.Remnawave("","https://sub.abc-vpn.example.com","",""),"https://abc-vpn.example.com","https://api.abc-vpn.example.com",
                "@support","privacy","terms","1","1","password-123","session-secret-123",2,2);
    }
}
