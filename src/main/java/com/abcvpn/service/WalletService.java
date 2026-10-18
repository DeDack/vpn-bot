package com.abcvpn.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.entity.Payment;
import com.abcvpn.entity.Tariff;
import com.abcvpn.entity.WalletAccount;
import com.abcvpn.entity.WalletTransaction;
import com.abcvpn.enumeration.PaymentStatus;
import com.abcvpn.enumeration.WalletTransactionStatus;
import com.abcvpn.enumeration.WalletTransactionType;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.PaymentRepository;
import com.abcvpn.repository.WalletRepository;

@Service
public class WalletService {
    private final WalletRepository walletRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionService subscriptionService;
    private final AbcVpnProperties properties;
    private final Clock clock;
    public WalletService(WalletRepository walletRepository,PaymentRepository paymentRepository,
                         SubscriptionService subscriptionService,AbcVpnProperties properties,Clock clock) {
        this.walletRepository=walletRepository;this.paymentRepository=paymentRepository;
        this.subscriptionService=subscriptionService;this.properties=properties;this.clock=clock;
    }
    public WalletAccount account(long telegramId) {return walletRepository.find(telegramId);}
    public List<WalletTransaction> history(long telegramId,int page,int size) {
        int safePage=Math.max(0,page);int safeSize=Math.min(10,Math.max(1,size));
        return walletRepository.history(telegramId,safeSize,safePage*safeSize);
    }
    @Transactional
    public PurchasePreparation preparePurchase(long telegramId,Tariff tariff,String actionKey) {
        if(actionKey==null||!actionKey.matches("[a-zA-Z0-9_-]{6,32}")) throw new FlowException(ErrorCode.INVALID_REQUEST);
        long price=kopecks(tariff.priceRub());
        String purchaseReference="purchase:"+telegramId+":"+actionKey;
        if(walletRepository.findByReference(purchaseReference).isPresent())
            return new PurchasePreparation(true,true,null,price,0);
        UUID paymentId=UUID.nameUUIDFromBytes(purchaseReference.getBytes(StandardCharsets.UTF_8));
        Payment existing=paymentRepository.findById(paymentId).orElse(null);
        if(existing!=null) return new PurchasePreparation(false,true,existing,existing.walletReservedKopecks(),kopecks(existing.amountRub()));
        WalletAccount account=walletRepository.findForUpdate(telegramId);
        if(account.availableKopecks()>=price) {
            if(!walletRepository.addCompleted(telegramId,price,"DEBIT",WalletTransactionType.SUBSCRIPTION_PAYMENT,
                    purchaseReference,null,null,"Оплата подписки: "+tariff.name(),clock.instant()))
                return new PurchasePreparation(true,true,null,price,0);
            if(!walletRepository.debitAvailable(telegramId,price)) throw new FlowException(ErrorCode.INVALID_REQUEST,"Недостаточно средств");
            subscriptionService.extend(telegramId,tariff.id(),tariff.durationDays());
            return new PurchasePreparation(true,false,null,price,0);
        }
        long reserved=account.availableKopecks();long external=price-reserved;
        Payment payment=new Payment(paymentId,telegramId,tariff.id(),null,rubles(external),null,reserved,
                PaymentStatus.PENDING,null,null,false,properties.termsVersion(),properties.privacyVersion(),
                clock.instant(),clock.instant(),null);
        paymentRepository.create(payment);
        if(reserved>0) {
            if(!walletRepository.addReserve(telegramId,reserved,paymentId,clock.instant())||!walletRepository.reserve(telegramId,reserved))
                throw new FlowException(ErrorCode.INVALID_REQUEST,"Не удалось зарезервировать баланс");
        }
        return new PurchasePreparation(false,false,payment,reserved,external);
    }
    @Transactional
    public void capture(UUID paymentId) {
        var reserve=walletRepository.findReserveForUpdate(paymentId).orElse(null);
        if(reserve==null||reserve.status()!=WalletTransactionStatus.PENDING) return;
        walletRepository.findForUpdate(reserve.telegramId());
        walletRepository.capture(reserve.telegramId(),reserve.amountKopecks(),reserve.id(),clock.instant());
    }
    @Transactional
    public void release(UUID paymentId) {
        var reserve=walletRepository.findReserveForUpdate(paymentId).orElse(null);
        if(reserve==null||reserve.status()!=WalletTransactionStatus.PENDING) return;
        walletRepository.findForUpdate(reserve.telegramId());
        walletRepository.release(reserve.telegramId(),reserve.amountKopecks(),reserve.id(),paymentId,clock.instant());
    }
    @Transactional
    public void cancelAndRelease(UUID paymentId) {
        paymentRepository.markCreationFailed(paymentId);
        release(paymentId);
    }
    @Transactional
    public boolean creditReferral(long referrerId,long referredId,UUID paymentId,long rewardKopecks) {
        if(rewardKopecks<=0) return false;
        walletRepository.findForUpdate(referrerId);
        boolean created=walletRepository.addCompleted(referrerId,rewardKopecks,"CREDIT",WalletTransactionType.REFERRAL_REWARD,
                "referral-payment:"+paymentId,paymentId,referredId,"Оплата реферала",clock.instant());
        if(created) walletRepository.credit(referrerId,rewardKopecks);
        return created;
    }
    public static long kopecks(BigDecimal rubles) {
        try {return rubles.movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact();}
        catch(ArithmeticException exception) {throw new FlowException(ErrorCode.INVALID_REQUEST,"Некорректная денежная сумма");}
    }
    public static BigDecimal rubles(long kopecks) {return BigDecimal.valueOf(kopecks,2);}
    public record PurchasePreparation(boolean completed,boolean reused,Payment payment,long balanceKopecks,long externalKopecks) {}
}
