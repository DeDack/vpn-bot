package com.abcvpn.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.repository.UserRepository;

@Service
public class TrialService {
    private final UserRepository userRepository;
    private final TariffRepository tariffRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionService subscriptionService;

    public TrialService(UserRepository userRepository,TariffRepository tariffRepository,
                        SubscriptionRepository subscriptionRepository,SubscriptionService subscriptionService) {
        this.userRepository=userRepository;this.tariffRepository=tariffRepository;
        this.subscriptionRepository=subscriptionRepository;this.subscriptionService=subscriptionService;
    }

    @Transactional
    public Subscription activate(long telegramId) {
        if (!userRepository.markTrialUsed(telegramId)) throw new FlowException(ErrorCode.TRIAL_ALREADY_USED);
        Tariff trial=tariffRepository.findByCode("TRIAL").orElseThrow(() -> new FlowException(ErrorCode.TARIFF_NOT_FOUND));
        subscriptionService.extend(telegramId,trial.id(),trial.durationDays());
        return subscriptionRepository.findByTelegramId(telegramId).orElseThrow();
    }
}
