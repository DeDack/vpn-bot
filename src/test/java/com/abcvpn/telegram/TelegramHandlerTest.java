package com.abcvpn.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.dto.PaymentLink;
import com.abcvpn.entity.User;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.enumeration.SourceType;
import com.abcvpn.enumeration.SyncStatus;
import com.abcvpn.service.PaymentService;
import com.abcvpn.service.DeviceService;
import com.abcvpn.service.ReferralService;
import com.abcvpn.service.SubscriptionService;
import com.abcvpn.service.SupportService;
import com.abcvpn.service.AnalyticsEventService;
import com.abcvpn.service.VpnAccessService;
import com.abcvpn.service.TariffService;
import com.abcvpn.service.TrialService;
import com.abcvpn.service.UserService;
import com.abcvpn.service.WalletService;
import com.abcvpn.service.ReturnBonusService;

class TelegramHandlerTest {
    @Test
    void referralScreenHasValidHtmlShareHistoryAndBalanceTariffs() throws Exception {
        TelegramClient client=mock(TelegramClient.class);UserService users=mock(UserService.class);
        ReferralService referrals=mock(ReferralService.class);TariffService tariffs=mock(TariffService.class);
        User user=new User(10,"user","Name",true,SourceType.ORGANIC,null,null,"abc123",false,Instant.now(),Instant.now());
        when(users.registerOrUpdate(10,"user","Name",null)).thenReturn(user);
        when(referrals.stats(10)).thenReturn(new ReferralService.ReferralStats(37_500,45_000,7_500,4,2));
        when(tariffs.referralWalletTariffs()).thenReturn(java.util.List.of(
                tariff(1,"REFERRAL_1D","1 день",1,"7"),tariff(2,"REFERRAL_7D","1 неделя",7,"49"),
                tariff(3,"BASIC","1 месяц",30,"150")));
        var handler=new TelegramHandler(client,users,mock(SubscriptionService.class),tariffs,mock(TrialService.class),
                mock(PaymentService.class),referrals,mock(WalletService.class),mock(DeviceService.class),mock(SupportService.class),
                mock(AnalyticsEventService.class),mock(VpnAccessService.class),mock(ReturnBonusService.class),properties(),Clock.systemUTC());

        handler.handle(new ObjectMapper().readTree("""
                {"callback_query":{"id":"invite","data":"invite","from":{"id":10,"username":"user","first_name":"Name"},
                "message":{"chat":{"id":10},"message_id":5}}}
                """));

        verify(client).edit(eq(10L),eq(5),contains("<a href=\"https://t.me/abc_vpn_support\">через поддержку</a>"),
                org.mockito.ArgumentMatchers.argThat(keyboard->keyboard.toString().contains("wallet_buy:1:")
                        &&keyboard.toString().contains("wallet_history:0")&&keyboard.toString().contains("t.me/share/url")));
    }
    @Test
    void oneClickLinkDoesNotEncodeSubscriptionInsideHappUri() {
        String link=TelegramHandler.oneClickUrl("https://api.abc-vpn.example.com",10,"secret",Instant.parse("2026-10-11T10:00:00Z"));
        assertThat(link).startsWith("https://api.abc-vpn.example.com/api/v1/redirect_dl?user=10&expires=1794304800&signature=");
        assertThat(link).doesNotContain("sub.abc-vpn.example.com");
    }

    @Test
    void firstStartActivatesTrialSynchronizesVpnAndShowsConnection() throws Exception {
        TelegramClient client=mock(TelegramClient.class);UserService users=mock(UserService.class);
        TrialService trials=mock(TrialService.class);SubscriptionService subscriptions=mock(SubscriptionService.class);
        AnalyticsEventService analytics=mock(AnalyticsEventService.class);VpnAccessService vpn=mock(VpnAccessService.class);
        Instant now=Instant.parse("2026-10-11T10:00:00Z");
        User user=new User(10,"user","Name",false,SourceType.CAMPAIGN,1L,null,"ref",false,now,now);
        Subscription subscription=new Subscription(10,1,now.plusSeconds(3*86400),false,"remote","https://sub.abc-vpn.example.com/key",
                SyncStatus.SYNCED,0,null,null,now,now);
        when(users.registerStart(10,"user","Name","ad_tg_01")).thenReturn(new UserService.Registration(user,true));
        when(trials.activate(10)).thenReturn(subscription);when(subscriptions.requireActive(10)).thenReturn(subscription);
        when(subscriptions.requireConnectionUrl(10)).thenReturn("https://sub.abc-vpn.example.com/key");
        var handler=new TelegramHandler(client,users,subscriptions,mock(TariffService.class),trials,mock(PaymentService.class),
                mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),mock(SupportService.class),analytics,vpn,mock(ReturnBonusService.class),properties(),Clock.fixed(now,java.time.ZoneOffset.UTC));

        handler.handle(new ObjectMapper().readTree("""
                {"update_id":103,"message":{"message_id":31,"text":"/start ad_tg_01","chat":{"id":10},
                "from":{"id":10,"username":"user","first_name":"Name"}}}
                """));

        verify(trials).activate(10);verify(vpn).sync(subscription);
        verify(analytics).record(10,AnalyticsEventService.TRIAL_AUTO_ACTIVATED);
        verify(analytics).record(10,AnalyticsEventService.CONNECTION_SCREEN_OPENED);
        verify(client).send(eq(10L),org.mockito.ArgumentMatchers.contains("⚡ <b>Подключение abc-vpn</b>"),any());
    }

    @Test
    void repeatedStartCompletesTrialAfterUserWasCreatedByFailedAttempt() throws Exception {
        TelegramClient client=mock(TelegramClient.class);UserService users=mock(UserService.class);
        TrialService trials=mock(TrialService.class);SubscriptionService subscriptions=mock(SubscriptionService.class);
        Instant now=Instant.parse("2026-10-11T10:00:00Z");
        User user=new User(10,"user","Name",false,SourceType.CAMPAIGN,1L,null,"ref",false,now,now);
        Subscription subscription=new Subscription(10,1,now.plusSeconds(259200),false,"remote","https://sub.abc-vpn.example.com/key",SyncStatus.SYNCED,0,null,null,now,now);
        when(users.registerStart(10,"user","Name","ad_tg_01")).thenReturn(new UserService.Registration(user,false));
        when(trials.activate(10)).thenReturn(subscription);when(subscriptions.requireActive(10)).thenReturn(subscription);
        when(subscriptions.requireConnectionUrl(10)).thenReturn("https://sub.abc-vpn.example.com/key");
        var handler=new TelegramHandler(client,users,subscriptions,mock(TariffService.class),trials,mock(PaymentService.class),
                mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),mock(SupportService.class),mock(AnalyticsEventService.class),
                mock(VpnAccessService.class),mock(ReturnBonusService.class),properties(),Clock.fixed(now,java.time.ZoneOffset.UTC));

        handler.handle(new ObjectMapper().readTree("""
                {"update_id":104,"message":{"text":"/start ad_tg_01","chat":{"id":10},
                "from":{"id":10,"username":"user","first_name":"Name"}}}
                """));

        verify(trials).activate(10);verify(users).registerStart(10,"user","Name","ad_tg_01");
    }

    @Test
    void usedExpiredTrialIsNeverIssuedAgain() throws Exception {
        UserService users=mock(UserService.class);TrialService trials=mock(TrialService.class);SubscriptionService subscriptions=mock(SubscriptionService.class);
        Instant now=Instant.parse("2026-10-11T10:00:00Z");
        User user=new User(10,"user","Name",true,SourceType.ORGANIC,null,null,"ref",false,now,now);
        Subscription expired=new Subscription(10,1,now.minusSeconds(1),false,"remote","https://sub.abc-vpn.example.com/key",SyncStatus.SYNCED,0,null,null,now,now);
        when(users.registerStart(10,"user","Name",null)).thenReturn(new UserService.Registration(user,false));
        when(subscriptions.find(10)).thenReturn(expired);
        var handler=new TelegramHandler(mock(TelegramClient.class),users,subscriptions,mock(TariffService.class),trials,
                mock(PaymentService.class),mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),mock(SupportService.class),
                mock(AnalyticsEventService.class),mock(VpnAccessService.class),mock(ReturnBonusService.class),properties(),Clock.fixed(now,java.time.ZoneOffset.UTC));

        handler.handle(new ObjectMapper().readTree("""
                {"update_id":105,"message":{"text":"/start","chat":{"id":10},
                "from":{"id":10,"username":"user","first_name":"Name"}}}
                """));

        verifyNoInteractions(trials);
    }

    @Test
    void repeatedStartSynchronizesPendingTrialWithoutCreatingAnotherOne() throws Exception {
        UserService users=mock(UserService.class);TrialService trials=mock(TrialService.class);SubscriptionService subscriptions=mock(SubscriptionService.class);
        TariffService tariffs=mock(TariffService.class);VpnAccessService vpn=mock(VpnAccessService.class);TelegramClient client=mock(TelegramClient.class);
        Instant now=Instant.parse("2026-10-11T10:00:00Z");
        User user=new User(10,"user","Name",true,SourceType.REFERRAL,null,9L,"ref",false,now,now);
        Subscription pending=new Subscription(10,1,now.plusSeconds(432000),false,null,null,SyncStatus.PENDING,0,null,null,now,now);
        Subscription synced=new Subscription(10,1,pending.expiresAt(),false,"remote","https://sub.abc-vpn.example.com/key",SyncStatus.SYNCED,0,null,null,now,now);
        Tariff trial=new Tariff(1,"TRIAL","Trial",3,BigDecimal.ZERO,10,null,UUID.randomUUID(),false,0);
        when(users.registerStart(10,"user","Name",null)).thenReturn(new UserService.Registration(user,false));
        when(subscriptions.find(10)).thenReturn(pending);when(subscriptions.requireActive(10)).thenReturn(synced);
        when(subscriptions.requireConnectionUrl(10)).thenReturn("https://sub.abc-vpn.example.com/key");when(tariffs.require(1)).thenReturn(trial);
        var handler=new TelegramHandler(client,users,subscriptions,tariffs,trials,mock(PaymentService.class),mock(ReferralService.class),mock(WalletService.class),
                mock(DeviceService.class),mock(SupportService.class),mock(AnalyticsEventService.class),vpn,mock(ReturnBonusService.class),properties(),Clock.fixed(now,java.time.ZoneOffset.UTC));

        handler.handle(new ObjectMapper().readTree("""
                {"update_id":106,"message":{"text":"/start","chat":{"id":10},
                "from":{"id":10,"username":"user","first_name":"Name"}}}
                """));

        verify(vpn).sync(pending);verifyNoInteractions(trials);
        verify(client).send(eq(10L),org.mockito.ArgumentMatchers.contains("⚡ <b>Подключение abc-vpn</b>"),any());
    }

    @Test
    void tariffSelectionImmediatelyCreatesPayment() throws Exception {
        TelegramClient client=mock(TelegramClient.class);
        UserService users=mock(UserService.class);
        PaymentService payments=mock(PaymentService.class);
        User user=new User(10,"user","Name",false,SourceType.ORGANIC,null,null,"ref",false,
                Instant.now(),Instant.now());
        when(users.registerOrUpdate(10,"user","Name",null)).thenReturn(user);
        when(payments.create(10,2)).thenReturn(new PaymentLink(UUID.randomUUID(),UUID.randomUUID(),
                "https://pay.example",Instant.now().plusSeconds(900),false));
        var handler=new TelegramHandler(client,users,mock(SubscriptionService.class),mock(TariffService.class),
                mock(TrialService.class),payments,mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),mock(SupportService.class),mock(AnalyticsEventService.class),mock(VpnAccessService.class),mock(ReturnBonusService.class),properties(),Clock.systemUTC());

        handler.handle(new ObjectMapper().readTree("""
                {"callback_query":{"id":"callback","data":"tariff:2","from":{"id":10,"username":"user","first_name":"Name"},
                "message":{"chat":{"id":10},"message_id":5}}}
                """));

        verify(payments).create(10,2);
        verify(client).edit(org.mockito.ArgumentMatchers.eq(10L),org.mockito.ArgumentMatchers.eq(5),
                org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ordinaryTextGoesToSupportButStartDoesNot() throws Exception {
        TelegramClient client=mock(TelegramClient.class);UserService users=mock(UserService.class);
        SupportService support=mock(SupportService.class);User user=new User(10,"user","Name",true,SourceType.ORGANIC,
                null,null,"ref",false,Instant.now(),Instant.now());
        when(users.registerOrUpdateWithStatus(10,"user","Name",null)).thenReturn(new UserService.Registration(user,false));
        when(users.registerStart(10,"user","Name",null)).thenReturn(new UserService.Registration(user,false));
        var handler=new TelegramHandler(client,users,mock(SubscriptionService.class),mock(TariffService.class),
                mock(TrialService.class),mock(PaymentService.class),mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),
                support,mock(AnalyticsEventService.class),mock(VpnAccessService.class),mock(ReturnBonusService.class),properties(),Clock.systemUTC());

        handler.handle(new ObjectMapper().readTree("""
                {"update_id":100,"message":{"message_id":5,"date":1791662400,"text":"Помогите",
                "chat":{"id":10},"from":{"id":10,"username":"user","first_name":"Name"}}}
                """));
        verify(support).forward(eq(100L),any(),eq(user));

        handler.handle(new ObjectMapper().readTree("""
                {"update_id":101,"message":{"message_id":6,"date":1791662401,"text":"/start",
                "chat":{"id":10},"from":{"id":10,"username":"user","first_name":"Name"}}}
                """));
        verify(support,never()).forward(eq(101L),any(),any());
    }

    @Test
    void configuredAdminReplyIsHandledWithoutUserRouting() throws Exception {
        TelegramClient client=mock(TelegramClient.class);UserService users=mock(UserService.class);
        SupportService support=mock(SupportService.class);when(support.isAdmin(99)).thenReturn(true);
        var handler=new TelegramHandler(client,users,mock(SubscriptionService.class),mock(TariffService.class),
                mock(TrialService.class),mock(PaymentService.class),mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),
                support,mock(AnalyticsEventService.class),mock(VpnAccessService.class),mock(ReturnBonusService.class),properties(),Clock.systemUTC());
        var update=new ObjectMapper().readTree("""
                {"update_id":102,"message":{"message_id":30,"date":1791662400,"text":"Ответ",
                "chat":{"id":99},"from":{"id":99},"reply_to_message":{"message_id":21}}}
                """);

        handler.handle(update);

        verify(support).reply(102,update.path("message"));
        verifyNoInteractions(users);
    }

    @Test
    void returnBonusCallbackSynchronizesExistingRemnawaveUser() throws Exception {
        Instant now=Instant.parse("2026-10-11T10:00:00Z");
        User user=new User(10,"user","Name",true,SourceType.ORGANIC,null,null,"ref",false,now,now);
        Subscription subscription=new Subscription(10,1,now.plusSeconds(172800),false,"remote","https://sub.abc-vpn.example.com/key",
                SyncStatus.PENDING,0,null,null,now,now);
        UserService users=mock(UserService.class);ReturnBonusService bonus=mock(ReturnBonusService.class);
        VpnAccessService vpn=mock(VpnAccessService.class);SubscriptionService subscriptions=mock(SubscriptionService.class);
        when(users.registerOrUpdate(10,"user","Name",null)).thenReturn(user);
        when(bonus.activate(10)).thenReturn(subscription);
        when(subscriptions.requireActive(10)).thenReturn(subscription);
        when(subscriptions.requireConnectionUrl(10)).thenReturn("https://sub.abc-vpn.example.com/key");
        var handler=new TelegramHandler(mock(TelegramClient.class),users,subscriptions,mock(TariffService.class),
                mock(TrialService.class),mock(PaymentService.class),mock(ReferralService.class),mock(WalletService.class),mock(DeviceService.class),
                mock(SupportService.class),mock(AnalyticsEventService.class),vpn,bonus,
                properties(),Clock.fixed(now,java.time.ZoneOffset.UTC));

        handler.handle(new ObjectMapper().readTree("""
                {"callback_query":{"id":"bonus","data":"return_bonus","from":{"id":10,"username":"user","first_name":"Name"},
                "message":{"chat":{"id":10},"message_id":5}}}
                """));

        verify(bonus).activate(10);
        verify(vpn).sync(subscription);
    }

    private AbcVpnProperties properties() {
        return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"bot",false),
                new AbcVpnProperties.Remnawave("","","",""),"https://abc-vpn.example.com","https://api.abc-vpn.example.com",
                "@support","privacy","terms","1","1","password-123","session-secret-123",1,3);
    }
    private Tariff tariff(long id,String code,String name,int days,String price) {
        return new Tariff(id,code,name,days,new BigDecimal(price),10,null,UUID.randomUUID(),true,1);
    }
}
