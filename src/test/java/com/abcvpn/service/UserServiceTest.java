package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import com.abcvpn.entity.Campaign;
import com.abcvpn.entity.User;
import com.abcvpn.enumeration.SourceType;
import com.abcvpn.repository.CampaignRepository;
import com.abcvpn.repository.UserRepository;
import com.abcvpn.telegram.TelegramClient;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    @Mock UserRepository users;@Mock CampaignRepository campaigns;@Mock TelegramClient telegram;@Mock AnalyticsEventService analytics;UserService service;
    Instant now=Instant.parse("2026-01-01T00:00:00Z");
    @BeforeEach void setUp(){service=new UserService(users,campaigns,telegram,analytics,Clock.fixed(now,ZoneOffset.UTC));}
    @Test void keepsFirstTouchForExistingUser(){User user=user(SourceType.ORGANIC,null,null);when(users.findById(10)).thenReturn(Optional.of(user));service.registerOrUpdate(10,"new","Name","ad_sale");verify(users).updateActivity(10,"new","Name",now);}
    @Test void storesCampaignOnFirstTouch(){Campaign c=new Campaign(5,"sale","Sale",null,true,now);when(users.findById(10)).thenReturn(Optional.empty());when(campaigns.findEnabledByCode("sale")).thenReturn(Optional.of(c));when(users.create(eq(10L),any(),any(),eq(SourceType.CAMPAIGN),eq(5L),eq(null),any(),eq(now))).thenReturn(user(SourceType.CAMPAIGN,5L,null));assertThat(service.registerOrUpdate(10,"u","N","ad_sale").sourceType()).isEqualTo(SourceType.CAMPAIGN);verify(telegram).notifyAdmin(contains("Новый пользователь"));}
    @Test void rejectsSelfReferralAsOrganic(){User self=user(SourceType.ORGANIC,null,null);when(users.findById(10)).thenReturn(Optional.empty());when(users.findByReferralCode("mine")).thenReturn(Optional.of(self));when(users.create(eq(10L),any(),any(),eq(SourceType.ORGANIC),eq(null),eq(null),any(),eq(now))).thenReturn(self);assertThat(service.registerOrUpdate(10,"u","N","ref_mine").sourceType()).isEqualTo(SourceType.ORGANIC);}
    @Test void storesValidReferralOnlyOnCreation(){User referrer=new User(9,"r","R",true,SourceType.ORGANIC,null,null,"friend",false,now,now);when(users.findById(10)).thenReturn(Optional.empty());when(users.findByReferralCode("friend")).thenReturn(Optional.of(referrer));when(users.create(eq(10L),any(),any(),eq(SourceType.REFERRAL),eq(null),eq(9L),any(),eq(now))).thenReturn(user(SourceType.REFERRAL,null,9L));assertThat(service.registerOrUpdate(10,"u","N","ref_friend").referrerTelegramId()).isEqualTo(9L);}
    @Test void existingReferrerCannotBeReplaced(){User existing=user(SourceType.REFERRAL,null,9L);when(users.findById(10)).thenReturn(Optional.of(existing));service.registerOrUpdate(10,"u","N","ref_other");verify(users,never()).findByReferralCode("other");verify(users).updateActivity(10,"u","N",now);}
    @Test void startEventCanBeRetriedWithoutChangingFirstTouch(){User user=user(SourceType.CAMPAIGN,5L,null);when(users.findById(10)).thenReturn(Optional.of(user));service.registerStart(10,"u","N","ad_other");service.registerStart(10,"u","N","ad_other");verify(analytics,org.mockito.Mockito.times(2)).record(10,AnalyticsEventService.BOT_START,now);verifyNoInteractions(campaigns);}
    private User user(SourceType source,Long campaign,Long referrer){return new User(10,"u","N",false,source,campaign,referrer,"mine",false,now,now);}
}
