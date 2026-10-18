package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.enumeration.SyncStatus;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class TrialServiceTest {
    @Mock UserRepository users;@Mock TariffRepository tariffs;@Mock SubscriptionRepository subscriptions;
    @Mock SubscriptionService subscriptionService;
    @Test void trialIsActivatedOnceWithoutLegacyReferralDays() {
        Instant now=Instant.parse("2026-01-01T00:00:00Z");
        Tariff tariff=new Tariff(1,"TRIAL","Trial",3,BigDecimal.ZERO,5,null,UUID.randomUUID(),false,0);
        Subscription subscription=new Subscription(1,1,now.plusSeconds(259200),false,null,null,SyncStatus.PENDING,0,null,null,now,now);
        when(users.markTrialUsed(1)).thenReturn(true);when(tariffs.findByCode("TRIAL")).thenReturn(Optional.of(tariff));
        when(subscriptions.findByTelegramId(1)).thenReturn(Optional.of(subscription));

        new TrialService(users,tariffs,subscriptions,subscriptionService).activate(1);

        verify(subscriptionService).extend(1,1,3);
    }
    @Test void secondTrialFails() {
        when(users.markTrialUsed(1)).thenReturn(false);
        TrialService service=new TrialService(users,tariffs,subscriptions,subscriptionService);
        assertThatThrownBy(()->service.activate(1)).isInstanceOf(FlowException.class);
        verifyNoInteractions(tariffs,subscriptions,subscriptionService);
    }
}
