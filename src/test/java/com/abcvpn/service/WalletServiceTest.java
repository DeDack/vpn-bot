package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.entity.Payment;
import com.abcvpn.entity.Tariff;
import com.abcvpn.entity.WalletAccount;
import com.abcvpn.entity.WalletTransaction;
import com.abcvpn.enumeration.WalletTransactionStatus;
import com.abcvpn.enumeration.WalletTransactionType;
import com.abcvpn.repository.PaymentRepository;
import com.abcvpn.repository.WalletRepository;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {
    @Mock WalletRepository wallets;@Mock PaymentRepository payments;@Mock SubscriptionService subscriptions;
    WalletService service;Tariff tariff;Instant now=Instant.parse("2026-01-01T00:00:00Z");
    @BeforeEach void setUp() {
        service=new WalletService(wallets,payments,subscriptions,props(),Clock.fixed(now,ZoneOffset.UTC));
        tariff=new Tariff(7,"BASIC","1 месяц",30,new BigDecimal("150"),10,null,UUID.randomUUID(),true,1);
    }
    @Test void fullBalancePaymentDebitsAndExtendsOnce() {
        when(wallets.findForUpdate(10)).thenReturn(account(37_500,0));
        when(wallets.addCompleted(org.mockito.ArgumentMatchers.eq(10L),org.mockito.ArgumentMatchers.eq(15_000L),
                org.mockito.ArgumentMatchers.eq("DEBIT"),org.mockito.ArgumentMatchers.eq(WalletTransactionType.SUBSCRIPTION_PAYMENT),
                any(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.isNull(),any(),any())).thenReturn(true);
        when(wallets.debitAvailable(10,15_000)).thenReturn(true);

        var result=service.preparePurchase(10,tariff,"action01");

        assertThat(result.completed()).isTrue();assertThat(result.balanceKopecks()).isEqualTo(15_000);
        verify(subscriptions).extend(10,7,30);verify(payments,never()).create(any());
    }
    @Test void repeatedActionDoesNotDebitOrExtendAgain() {
        when(wallets.findByReference("purchase:10:action01")).thenReturn(Optional.of(transaction()));

        var result=service.preparePurchase(10,tariff,"action01");

        assertThat(result.completed()).isTrue();assertThat(result.reused()).isTrue();
        verify(wallets,never()).debitAvailable(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong());
        verify(subscriptions,never()).extend(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test void partialBalanceIsReservedAndPaymentContainsOnlyRemainder() {
        when(wallets.findForUpdate(10)).thenReturn(account(8_000,0));
        when(wallets.addReserve(org.mockito.ArgumentMatchers.eq(10L),org.mockito.ArgumentMatchers.eq(8_000L),any(),any())).thenReturn(true);
        when(wallets.reserve(10,8_000)).thenReturn(true);

        var result=service.preparePurchase(10,tariff,"action02");

        assertThat(result.completed()).isFalse();assertThat(result.balanceKopecks()).isEqualTo(8_000);
        assertThat(result.externalKopecks()).isEqualTo(7_000);
        verify(payments).create(org.mockito.ArgumentMatchers.argThat(payment->payment.amountRub().compareTo(new BigDecimal("70.00"))==0
                && payment.walletReservedKopecks()==8_000));
        verify(subscriptions,never()).extend(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test void canceledPaymentReleasesReserveOnce() {
        UUID paymentId=UUID.randomUUID();WalletTransaction reserve=new WalletTransaction(5,10,8_000,"DEBIT",
                WalletTransactionType.SUBSCRIPTION_PAYMENT_RESERVE,WalletTransactionStatus.PENDING,"reserve",paymentId,null,null,now,null);
        when(wallets.findReserveForUpdate(paymentId)).thenReturn(Optional.of(reserve));when(wallets.findForUpdate(10)).thenReturn(account(0,8_000));

        service.release(paymentId);

        verify(wallets).release(10,8_000,5,paymentId,now);
    }
    @Test void historyUsesBoundedPagination() {
        when(wallets.history(10,6,12)).thenReturn(java.util.List.of(transaction()));

        assertThat(service.history(10,2,6)).hasSize(1);

        verify(wallets).history(10,6,12);
    }
    private WalletAccount account(long available,long reserved) {return new WalletAccount(10,available,reserved,0,0);}
    private WalletTransaction transaction() {return new WalletTransaction(1,10,15_000,"DEBIT",WalletTransactionType.SUBSCRIPTION_PAYMENT,
            WalletTransactionStatus.COMPLETED,"purchase:10:action01",null,null,null,now,now);}
    private AbcVpnProperties props() {return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"bot",false),
            new AbcVpnProperties.Remnawave("","","",""),"https://vpn.test","https://api.test","@support","p","t","1","1",
            "password-123","session-secret-123",2,7);}
}
