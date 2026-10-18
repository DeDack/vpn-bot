package com.abcvpn.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.config.PlategaProperties;
import com.abcvpn.dto.PaymentLink;
import com.abcvpn.dto.PlategaCallback;
import com.abcvpn.entity.Payment;
import com.abcvpn.entity.Tariff;
import com.abcvpn.enumeration.PaymentStatus;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.PaymentRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.repository.UserRepository;

@Service
public class PaymentService {
    private static final Logger log=LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository paymentRepository;
    private final TariffService tariffService;
    private final TariffRepository tariffRepository;
    private final SubscriptionService subscriptionService;
    private final ReferralService referralService;
    private final PlategaClient plategaClient;
    private final PlategaProperties properties;
    private final AbcVpnProperties abcVpnProperties;
    private final UserRepository userRepository;
    private final WalletService walletService;
    private final Clock clock;

    public PaymentService(PaymentRepository paymentRepository,TariffService tariffService,
                          TariffRepository tariffRepository,SubscriptionService subscriptionService,
                          ReferralService referralService,PlategaClient plategaClient,
                          PlategaProperties properties,AbcVpnProperties abcVpnProperties,
                          UserRepository userRepository,WalletService walletService,Clock clock) {
        this.paymentRepository=paymentRepository;this.tariffService=tariffService;this.tariffRepository=tariffRepository;
        this.subscriptionService=subscriptionService;this.referralService=referralService;
        this.plategaClient=plategaClient;this.properties=properties;this.abcVpnProperties=abcVpnProperties;
        this.userRepository=userRepository;this.walletService=walletService;this.clock=clock;
    }

    public PaymentLink create(long telegramId,long tariffId) {
        if(!properties.enabled()) throw new FlowException(ErrorCode.PAYMENT_DISABLED);
        Tariff tariff=tariffService.requirePurchasable(tariffId);
        Instant now=clock.instant();
        Payment existing=paymentRepository.findReusable(telegramId,tariff.id(),now).orElse(null);
        if(existing!=null) {
            if(existing.creationUncertain()) throw new FlowException(ErrorCode.PAYMENT_CREATION_UNCERTAIN);
            return new PaymentLink(existing.id(),existing.plategaTransactionId(),existing.paymentUrl(),existing.paymentExpiresAt(),true);
        }
        UUID id=UUID.randomUUID();
        Payment payment=new Payment(id,telegramId,tariff.id(),null,tariff.priceRub(),null,0,PaymentStatus.PENDING,
                null,null,false,abcVpnProperties.termsVersion(),abcVpnProperties.privacyVersion(),now,now,null);
        paymentRepository.create(payment);
        return createProviderPayment(payment,tariff,false);
    }

    public PurchaseResult createWithBalance(long telegramId,long tariffId,String actionKey) {
        Tariff tariff=tariffService.requireWalletPurchasable(tariffId);
        WalletService.PurchasePreparation preparation=walletService.preparePurchase(telegramId,tariff,actionKey);
        if(preparation.completed()) return new PurchaseResult(true,preparation.reused(),null,
                WalletService.rubles(preparation.balanceKopecks()),BigDecimal.ZERO);
        Payment payment=preparation.payment();
        if(payment.creationUncertain()) throw new FlowException(ErrorCode.PAYMENT_CREATION_UNCERTAIN);
        if(!properties.enabled()) {
            walletService.cancelAndRelease(payment.id());
            throw new FlowException(ErrorCode.PAYMENT_DISABLED);
        }
        PaymentLink link;
        if(payment.paymentUrl()!=null&&!payment.paymentUrl().isBlank())
            link=new PaymentLink(payment.id(),payment.plategaTransactionId(),payment.paymentUrl(),payment.paymentExpiresAt(),true);
        else link=createProviderPayment(payment,tariff,preparation.reused());
        return new PurchaseResult(false,preparation.reused(),link,WalletService.rubles(preparation.balanceKopecks()),
                WalletService.rubles(preparation.externalKopecks()));
    }

    private PaymentLink createProviderPayment(Payment payment,Tariff tariff,boolean reused) {
        UUID id=payment.id();
        paymentRepository.markCreationUncertain(id);
        String username=userRepository.findById(payment.telegramId()).map(user->user.username()).orElse(null);
        PlategaClient.CreatedPayment created;
        try {
            created=plategaClient.create(payment.amountRub(),payment.telegramId(),username,"abc-vpn: "+tariff.name(),id);
        } catch(PlategaClient.PlategaException exception) {
            if(!exception.uncertain()) walletService.cancelAndRelease(id);
            ErrorCode code=exception.uncertain()?ErrorCode.PAYMENT_CREATION_UNCERTAIN:ErrorCode.EXTERNAL_SERVICE_ERROR;
            throw new FlowException(code,code.message(),exception);
        }
        paymentRepository.attachProviderData(id,created.transactionId(),created.url(),created.expiresAt());
        log.info("[PAYMENT] Created payment {} with transaction {}",id,created.transactionId());
        return new PaymentLink(id,created.transactionId(),created.url(),created.expiresAt(),reused);
    }

    @Transactional
    public CallbackResult processCallback(PlategaCallback callback) {
        Payment payment=paymentRepository.findByTransactionIdForUpdate(callback.id())
                .orElseThrow(() -> new FlowException(ErrorCode.PAYMENT_NOT_FOUND));
        validateCallback(callback);
        if("PENDING".equals(callback.status())) return new CallbackResult(payment.telegramId(),false,false);
        if("CONFIRMED".equals(callback.status())) {
            validateConfirmedAmount(callback,payment);
            if(payment.status()==PaymentStatus.PAID) return new CallbackResult(payment.telegramId(),false,true);
            if(payment.status()!=PaymentStatus.PENDING) throw new FlowException(ErrorCode.INVALID_PAYMENT_CALLBACK);
            if(!paymentRepository.markPaid(payment.id(),callback.amount(),clock.instant())) return new CallbackResult(payment.telegramId(),false,true);
            walletService.capture(payment.id());
            Tariff tariff=tariffRepository.findById(payment.tariffId()).orElseThrow();
            subscriptionService.extend(payment.telegramId(),tariff.id(),tariff.durationDays());
            referralService.reward(payment,callback.amount());
            log.info("[PAYMENT] Confirmed payment {}",payment.id());
            return new CallbackResult(payment.telegramId(),true,true);
        }
        if("CANCELED".equals(callback.status())||"FAILED".equals(callback.status())||"EXPIRED".equals(callback.status())) {
            if(payment.status()!=PaymentStatus.PENDING) return new CallbackResult(payment.telegramId(),false,false);
            paymentRepository.markCanceled(payment.id());walletService.release(payment.id());
        }
        else if("CHARGEBACKED".equals(callback.status())) paymentRepository.markChargeback(payment.id());
        else throw new FlowException(ErrorCode.INVALID_PAYMENT_CALLBACK,"Неизвестный статус платежа");
        return new CallbackResult(payment.telegramId(),false,false);
    }

    public boolean validCredentials(String merchantId,String secret) {
        return properties.enabled()&&constantTime(properties.merchantId(),merchantId)&&constantTime(properties.secret(),secret);
    }
    private void validateCallback(PlategaCallback callback) {
        if(callback.id()==null||callback.amount()==null||callback.currency()==null||callback.status()==null
                ||!"RUB".equals(callback.currency()))
            throw new FlowException(ErrorCode.INVALID_PAYMENT_CALLBACK);
    }
    private void validateConfirmedAmount(PlategaCallback callback,Payment payment) {
        if(callback.amount().compareTo(payment.amountRub())<0)
            throw new FlowException(ErrorCode.INVALID_PAYMENT_CALLBACK);
    }

    public PlategaCallback checkStatus(UUID paymentId) {
        Payment payment=paymentRepository.findById(paymentId).orElseThrow(()->new FlowException(ErrorCode.PAYMENT_NOT_FOUND));
        if(payment.plategaTransactionId()==null) throw new FlowException(ErrorCode.PAYMENT_CREATION_UNCERTAIN);
        PlategaClient.TransactionStatus status;
        try { status=plategaClient.getStatus(payment.plategaTransactionId()); }
        catch(PlategaClient.PlategaException exception) { throw new FlowException(ErrorCode.EXTERNAL_SERVICE_ERROR,ErrorCode.EXTERNAL_SERVICE_ERROR.message(),exception); }
        return new PlategaCallback(status.id(),status.amount(),status.currency(),status.status(),null);
    }
    private boolean constantTime(String expected,String actual) {
        if(expected==null||actual==null) return false;
        return java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public record CallbackResult(long telegramId,boolean notifyUser,boolean paid) {}
    public record PurchaseResult(boolean completed,boolean reused,PaymentLink paymentLink,
                                 BigDecimal balanceApplied,BigDecimal externalAmount) {}
}
