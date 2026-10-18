package com.abcvpn.service;

import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Subscription;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.PaymentRepository;
import com.abcvpn.repository.ReturnBonusRepository;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.repository.UserRepository;

@Service
public class ReturnBonusService {
    private static final int BONUS_DAYS=2;
    private final UserRepository userRepository;private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;private final PaymentRepository paymentRepository;
    private final ReturnBonusRepository bonusRepository;private final SubscriptionService subscriptionService;private final Clock clock;
    public ReturnBonusService(UserRepository userRepository,SubscriptionRepository subscriptionRepository,TariffRepository tariffRepository,
                              PaymentRepository paymentRepository,ReturnBonusRepository bonusRepository,
                              SubscriptionService subscriptionService,Clock clock) {
        this.userRepository=userRepository;this.subscriptionRepository=subscriptionRepository;this.tariffRepository=tariffRepository;
        this.paymentRepository=paymentRepository;this.bonusRepository=bonusRepository;this.subscriptionService=subscriptionService;this.clock=clock;
    }
    @Transactional
    public Subscription activate(long telegramId) {
        var user=userRepository.findByIdForUpdate(telegramId).orElseThrow(()->new FlowException(ErrorCode.USER_NOT_FOUND));
        Subscription subscription=subscriptionRepository.findByTelegramIdForUpdate(telegramId)
                .orElseThrow(()->new FlowException(ErrorCode.RETURN_BONUS_UNAVAILABLE));
        var tariff=tariffRepository.findById(subscription.tariffId()).orElseThrow();
        if(!user.trialUsed()||!"TRIAL".equals(tariff.code())||subscription.expiresAt()==null||
                subscription.expiresAt().isAfter(clock.instant())||paymentRepository.hasPaidPayment(telegramId)||
                !bonusRepository.hasReliableNoTrafficHistory(telegramId))
            throw new FlowException(ErrorCode.RETURN_BONUS_UNAVAILABLE);
        if(!bonusRepository.create(telegramId,clock.instant(),subscription.expiresAt()))
            throw new FlowException(ErrorCode.RETURN_BONUS_ALREADY_USED);
        subscriptionService.grant(telegramId,subscription.tariffId(),BONUS_DAYS);
        return subscriptionRepository.findByTelegramId(telegramId).orElseThrow();
    }
}
