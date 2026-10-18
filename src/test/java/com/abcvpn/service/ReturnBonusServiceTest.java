package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.abcvpn.entity.*;
import com.abcvpn.enumeration.*;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.*;

class ReturnBonusServiceTest {
    private final UserRepository users=mock(UserRepository.class);
    private final SubscriptionRepository subscriptions=mock(SubscriptionRepository.class);
    private final TariffRepository tariffs=mock(TariffRepository.class);
    private final PaymentRepository payments=mock(PaymentRepository.class);
    private final ReturnBonusRepository bonuses=mock(ReturnBonusRepository.class);
    private final SubscriptionService subscriptionService=mock(SubscriptionService.class);
    private final Instant now=Instant.parse("2026-10-11T10:00:00Z");
    private final ReturnBonusService service=new ReturnBonusService(users,subscriptions,tariffs,payments,bonuses,subscriptionService,Clock.fixed(now,ZoneOffset.UTC));

    @Test void grantsTwoDaysFromActivationMomentOnce() {
        arrangeEligible();
        Subscription granted=subscription(now.plus(2,java.time.temporal.ChronoUnit.DAYS));
        when(bonuses.create(10,now,now.minusSeconds(60))).thenReturn(true);
        when(subscriptions.findByTelegramId(10)).thenReturn(Optional.of(granted));

        service.activate(10);

        verify(subscriptionService).grant(10,1,2);
    }

    @Test void repeatedOrConcurrentActivationDoesNotGrantAgain() {
        arrangeEligible();
        when(bonuses.create(10,now,now.minusSeconds(60))).thenReturn(false);

        assertThatThrownBy(()->service.activate(10)).isInstanceOf(FlowException.class);

        verifyNoInteractions(subscriptionService);
    }

    @Test void trafficOrUnknownHistoryMakesBonusUnavailable() {
        arrangeEligible();
        when(bonuses.hasReliableNoTrafficHistory(10)).thenReturn(false);

        assertThatThrownBy(()->service.activate(10)).isInstanceOf(FlowException.class);

        verify(bonuses,never()).create(anyLong(),any(),any());
        verifyNoInteractions(subscriptionService);
    }

    private void arrangeEligible() {
        User user=new User(10,"user","Name",true,SourceType.ORGANIC,null,null,"ref",false,now.minusSeconds(100000),now);
        Tariff tariff=new Tariff(1,"TRIAL","Trial",3,BigDecimal.ZERO,10,null,UUID.randomUUID(),true,0);
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(user));
        when(subscriptions.findByTelegramIdForUpdate(10)).thenReturn(Optional.of(subscription(now.minusSeconds(60))));
        when(tariffs.findById(1)).thenReturn(Optional.of(tariff));
        when(bonuses.hasReliableNoTrafficHistory(10)).thenReturn(true);
    }

    private Subscription subscription(Instant expiresAt) {
        return new Subscription(10,1,expiresAt,false,"remote",null,SyncStatus.SYNCED,0,null,null,now.minusSeconds(100000),now);
    }
}
