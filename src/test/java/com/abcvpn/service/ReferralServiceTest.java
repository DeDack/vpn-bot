package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.abcvpn.entity.Payment;
import com.abcvpn.entity.User;
import com.abcvpn.enumeration.PaymentStatus;
import com.abcvpn.enumeration.SourceType;
import com.abcvpn.repository.UserRepository;
import com.abcvpn.repository.WalletRepository;

class ReferralServiceTest {
    private static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    @Test void rewardIsFiftyPercentOfExternalPayment() {
        UserRepository users=org.mockito.Mockito.mock(UserRepository.class);
        WalletRepository wallets=org.mockito.Mockito.mock(WalletRepository.class);
        WalletService walletService=org.mockito.Mockito.mock(WalletService.class);
        Payment payment=payment(UUID.randomUUID());
        when(users.findById(2)).thenReturn(Optional.of(referred()));
        when(walletService.creditReferral(1,2,payment.id(),5_000)).thenReturn(true);

        assertThat(service(users,wallets,walletService).reward(payment,new BigDecimal("100.00"))).isTrue();

        verify(walletService).creditReferral(1,2,payment.id(),5_000);
    }
    @Test void everyDifferentSuccessfulPaymentCanRewardAgain() {
        UserRepository users=org.mockito.Mockito.mock(UserRepository.class);
        WalletService walletService=org.mockito.Mockito.mock(WalletService.class);
        when(users.findById(2)).thenReturn(Optional.of(referred()));
        Payment first=payment(UUID.randomUUID()),second=payment(UUID.randomUUID());
        ReferralService service=service(users,org.mockito.Mockito.mock(WalletRepository.class),walletService);

        service.reward(first,new BigDecimal("150"));service.reward(second,new BigDecimal("150"));

        verify(walletService).creditReferral(1,2,first.id(),7_500);
        verify(walletService).creditReferral(1,2,second.id(),7_500);
    }
    @Test void userWithoutReferrerGetsNoReward() {
        UserRepository users=org.mockito.Mockito.mock(UserRepository.class);
        WalletService walletService=org.mockito.Mockito.mock(WalletService.class);
        when(users.findById(2)).thenReturn(Optional.of(new User(2,"u","n",true,SourceType.ORGANIC,null,null,"r",false,NOW,NOW)));

        assertThat(service(users,org.mockito.Mockito.mock(WalletRepository.class),walletService)
                .reward(payment(UUID.randomUUID()),new BigDecimal("150"))).isFalse();

        verify(walletService,never()).creditReferral(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyLong());
    }
    private ReferralService service(UserRepository users,WalletRepository wallets,WalletService walletService) {
        return new ReferralService(users,wallets,walletService,Clock.fixed(NOW,ZoneOffset.UTC));
    }
    private User referred() {return new User(2,"u","n",true,SourceType.REFERRAL,null,1L,"r",false,NOW,NOW);}
    private Payment payment(UUID id) {return new Payment(id,2,7,UUID.randomUUID(),new BigDecimal("150"),null,0,
            PaymentStatus.PENDING,"https://pay",NOW.plusSeconds(900),false,"1","1",NOW,NOW,null);}
}
