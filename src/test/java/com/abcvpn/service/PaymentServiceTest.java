package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
import com.abcvpn.config.PlategaProperties;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.dto.PlategaCallback;
import com.abcvpn.entity.Payment;
import com.abcvpn.entity.Tariff;
import com.abcvpn.entity.User;
import com.abcvpn.enumeration.PaymentStatus;
import com.abcvpn.enumeration.ReferralEvent;
import com.abcvpn.enumeration.SourceType;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.PaymentRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {
    @Mock PaymentRepository payments; @Mock TariffService tariffService; @Mock TariffRepository tariffs;
    @Mock SubscriptionService subscriptions; @Mock ReferralService referrals; @Mock PlategaClient client;
    @Mock UserRepository users; @Mock WalletService wallets;
    PaymentService service; Instant now=Instant.parse("2026-01-01T00:00:00Z");
    UUID tx=UUID.randomUUID(),id=UUID.randomUUID(); Tariff tariff;

    @BeforeEach void setUp() {
        tariff=new Tariff(7,"M1","1 месяц",30,new BigDecimal("199"),5,null,UUID.randomUUID(),true,1);
        service=service(true);
    }

    @Test void createsPayment() {
        when(tariffService.requirePurchasable(7)).thenReturn(tariff);
        when(users.findById(10)).thenReturn(Optional.of(user()));
        when(client.create(eq(new BigDecimal("199")),eq(10L),eq("user"),any(),any()))
                .thenReturn(new PlategaClient.CreatedPayment(tx,"https://pay",now.plusSeconds(900)));
        var result=service.create(10,7);
        assertThat(result.transactionId()).isEqualTo(tx);
        verify(payments).markCreationUncertain(result.paymentId());
        verify(payments).attachProviderData(result.paymentId(),tx,"https://pay",now.plusSeconds(900));
    }

    @Test void reusesPendingPayment() {
        when(tariffService.requirePurchasable(7)).thenReturn(tariff);
        when(payments.findReusable(10,7,now)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        assertThat(service.create(10,7).reused()).isTrue();
        verifyNoInteractions(client);
    }

    @Test void fullWalletPaymentDoesNotCallPlatega() {
        when(tariffService.requireWalletPurchasable(7)).thenReturn(tariff);
        when(wallets.preparePurchase(10,tariff,"action01"))
                .thenReturn(new WalletService.PurchasePreparation(true,false,null,19_900,0));

        var result=service.createWithBalance(10,7,"action01");

        assertThat(result.completed()).isTrue();verifyNoInteractions(client);
    }

    @Test void mixedWalletPaymentCreatesInvoiceOnlyForRemainder() {
        Payment mixed=new Payment(id,10,7,null,new BigDecimal("70"),null,12_900,PaymentStatus.PENDING,
                null,null,false,"1","1",now,now,null);
        when(tariffService.requireWalletPurchasable(7)).thenReturn(tariff);
        when(wallets.preparePurchase(10,tariff,"action02"))
                .thenReturn(new WalletService.PurchasePreparation(false,false,mixed,12_900,7_000));
        when(users.findById(10)).thenReturn(Optional.of(user()));
        when(client.create(new BigDecimal("70"),10,"user","abc-vpn: 1 месяц",id))
                .thenReturn(new PlategaClient.CreatedPayment(tx,"https://pay",now.plusSeconds(900)));

        var result=service.createWithBalance(10,7,"action02");

        assertThat(result.externalAmount()).isEqualByComparingTo("70.00");
        verify(client).create(new BigDecimal("70"),10,"user","abc-vpn: 1 месяц",id);
    }

    @Test void timeoutLeavesCreationUncertain() {
        when(tariffService.requirePurchasable(7)).thenReturn(tariff);
        when(client.create(any(),anyLong(),any(),any(),any())).thenThrow(new PlategaClient.PlategaException(true,"timeout"));
        assertThatThrownBy(()->service.create(10,7)).isInstanceOf(FlowException.class);
        verify(payments,never()).markCreationFailed(any());
    }

    @Test void uncertainMixedPaymentDoesNotCreateDuplicateInvoice() {
        Payment uncertain=new Payment(id,10,7,null,new BigDecimal("70"),null,12_900,PaymentStatus.PENDING,
                null,null,true,"1","1",now,now,null);
        when(tariffService.requireWalletPurchasable(7)).thenReturn(tariff);
        when(wallets.preparePurchase(10,tariff,"action03"))
                .thenReturn(new WalletService.PurchasePreparation(false,true,uncertain,12_900,7_000));

        assertThatThrownBy(()->service.createWithBalance(10,7,"action03")).isInstanceOf(FlowException.class);

        verifyNoInteractions(client);
    }

    @Test void deterministicCreationErrorCancelsLocalPayment() {
        when(tariffService.requirePurchasable(7)).thenReturn(tariff);
        when(client.create(any(),anyLong(),any(),any(),any())).thenThrow(new PlategaClient.PlategaException(false,"bad request"));
        assertThatThrownBy(()->service.create(10,7)).isInstanceOf(FlowException.class);
        verify(wallets).cancelAndRelease(any());
    }

    @Test void paymentDisabledDoesNotCallPlatega() {
        service=service(false);
        assertThatThrownBy(()->service.create(10,7)).isInstanceOf(FlowException.class);
        verifyNoInteractions(client);
    }

    @Test void confirmedExtendsOnlyOnce() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        when(payments.markPaid(id,new BigDecimal("199"),now)).thenReturn(true);
        when(tariffs.findById(7)).thenReturn(Optional.of(tariff));
        assertThat(service.processCallback(callback("CONFIRMED",new BigDecimal("199"),"RUB")).notifyUser()).isTrue();
        verify(wallets).capture(id);verify(subscriptions).extend(10,7,30);
        verify(referrals).reward(any(Payment.class),eq(new BigDecimal("199")));
    }

    @Test void repeatedConfirmedDoesNotExtend() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PAID)));
        service.processCallback(callback("CONFIRMED",new BigDecimal("199"),"RUB"));
        verifyNoInteractions(subscriptions,referrals);
    }

    @Test void mixedPaymentRewardsOnlyExternalAmount() {
        Payment mixed=new Payment(id,10,7,tx,new BigDecimal("70"),null,8_000,PaymentStatus.PENDING,
                "https://pay",now.plusSeconds(100),false,"1","1",now,now,null);
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(mixed));
        when(payments.markPaid(id,new BigDecimal("70"),now)).thenReturn(true);
        when(tariffs.findById(7)).thenReturn(Optional.of(tariff));

        service.processCallback(callback("CONFIRMED",new BigDecimal("70"),"RUB"));

        verify(wallets).capture(id);verify(referrals).reward(mixed,new BigDecimal("70"));
        verify(subscriptions).extend(10,7,30);
    }

    @Test void concurrentConditionalUpdateDoesNotExtendTwice() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        when(payments.markPaid(id,new BigDecimal("199"),now)).thenReturn(false);
        service.processCallback(callback("CONFIRMED",new BigDecimal("199"),"RUB"));
        verifyNoInteractions(subscriptions,referrals);
    }

    @Test void rejectsUnderpayment() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        assertThatThrownBy(()->service.processCallback(callback("CONFIRMED",new BigDecimal("198.99"),"RUB"))).isInstanceOf(FlowException.class);
    }

    @Test void rejectsWrongCurrency() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        assertThatThrownBy(()->service.processCallback(callback("CONFIRMED",new BigDecimal("199"),"USD"))).isInstanceOf(FlowException.class);
    }

    @Test void pendingDoesNotChangeBusinessState() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        service.processCallback(callback("PENDING",new BigDecimal("199"),"RUB"));
        verify(payments,never()).markPaid(any(),any(),any());verifyNoInteractions(subscriptions);
    }

    @Test void failedWithZeroAmountReleasesReservedBalance() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        service.processCallback(callback("FAILED",BigDecimal.ZERO,"RUB"));
        verify(payments).markCanceled(id);verify(wallets).release(id);verifyNoInteractions(subscriptions,referrals);
    }

    @Test void canceledWithZeroAmountReleasesReservedBalance() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));
        service.processCallback(callback("CANCELED",BigDecimal.ZERO,"RUB"));
        verify(payments).markCanceled(id);verify(wallets).release(id);verifyNoInteractions(subscriptions,referrals);
    }

    @Test void expiredWithZeroAmountReleasesReservedBalance() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)));

        service.processCallback(callback("EXPIRED",BigDecimal.ZERO,"RUB"));

        verify(payments).markCanceled(id);verify(wallets).release(id);verifyNoInteractions(subscriptions,referrals);
    }

    @Test void repeatedTerminalCallbackDoesNotReleaseTwice() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PENDING)),
                Optional.of(payment(PaymentStatus.CANCELED)));

        service.processCallback(callback("CANCELED",BigDecimal.ZERO,"RUB"));
        service.processCallback(callback("CANCELED",BigDecimal.ZERO,"RUB"));

        verify(wallets,times(1)).release(id);verify(payments,times(1)).markCanceled(id);
        verifyNoInteractions(subscriptions,referrals);
    }

    @Test void chargebackIsVisibleWithoutAutomaticRevoke() {
        when(payments.findByTransactionIdForUpdate(tx)).thenReturn(Optional.of(payment(PaymentStatus.PAID)));
        service.processCallback(callback("CHARGEBACKED",new BigDecimal("199"),"RUB"));
        verify(payments).markChargeback(id);verifyNoInteractions(subscriptions);
    }

    @Test void validatesCallbackCredentials() {
        assertThat(service.validCredentials("merchant","secret")).isTrue();
        assertThat(service.validCredentials("merchant","wrong")).isFalse();
    }

    @Test void statusLookupReconcilesPayment() {
        Payment pending=payment(PaymentStatus.PENDING);
        when(payments.findById(id)).thenReturn(Optional.of(pending));
        when(client.getStatus(tx)).thenReturn(new PlategaClient.TransactionStatus(tx,"PENDING",new BigDecimal("199"),"RUB","SBPQR",id.toString()));
        assertThat(service.checkStatus(id).status()).isEqualTo("PENDING");
    }

    private PaymentService service(boolean enabled) {
        return new PaymentService(payments,tariffService,tariffs,subscriptions,referrals,client,
                new PlategaProperties(enabled,"https://app.platega.io/","merchant","secret"),props(),users,wallets,
                Clock.fixed(now,ZoneOffset.UTC));
    }
    private Payment payment(PaymentStatus status) {return new Payment(id,10,7,tx,new BigDecimal("199"),null,0,status,"https://pay",now.plusSeconds(100),false,"1","1",now,now,status==PaymentStatus.PAID?now:null);}
    private PlategaCallback callback(String status,BigDecimal amount,String currency) {return new PlategaCallback(tx,amount,currency,status,2);}
    private User user(){return new User(10,"user","Name",false,SourceType.ORGANIC,null,null,"ref",false,now,now);}
    private AbcVpnProperties props(){return new AbcVpnProperties(new AbcVpnProperties.Telegram("",0,"bot",false),new AbcVpnProperties.Remnawave("","","",""),"https://vpn.test","https://api.vpn.test","@s","p","t","1","1","password-123","session-secret-123",1,3);}
}
