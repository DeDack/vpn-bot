package com.abcvpn.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Payment;
import com.abcvpn.entity.WalletAccount;
import com.abcvpn.repository.UserRepository;
import com.abcvpn.repository.WalletRepository;

@Service
public class ReferralService {
    public static final int REWARD_PERCENT=50;
    public static final long MIN_WITHDRAWAL_KOPECKS=50_000;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final Clock clock;

    public ReferralService(UserRepository userRepository,WalletRepository walletRepository,
                           WalletService walletService,Clock clock) {
        this.userRepository=userRepository;this.walletRepository=walletRepository;
        this.walletService=walletService;this.clock=clock;
    }
    @Transactional
    public boolean reward(Payment payment,BigDecimal externalPaidRub) {
        var referred=userRepository.findById(payment.telegramId()).orElse(null);
        if(referred==null||referred.referrerTelegramId()==null||externalPaidRub==null||externalPaidRub.signum()<=0) return false;
        long paidKopecks=WalletService.kopecks(externalPaidRub);
        long reward=BigDecimal.valueOf(paidKopecks).multiply(BigDecimal.valueOf(REWARD_PERCENT))
                .divide(BigDecimal.valueOf(100),0,RoundingMode.DOWN).longValueExact();
        return walletService.creditReferral(referred.referrerTelegramId(),referred.telegramId(),payment.id(),reward);
    }
    public ReferralStats stats(long telegramId) {
        WalletAccount account=walletRepository.find(telegramId);
        WalletRepository.ReferralStats stats=walletRepository.referralStats(telegramId,clock.instant());
        return new ReferralStats(account.availableKopecks(),account.totalEarnedKopecks(),account.totalSpentKopecks(),
                stats.invited(),stats.activePaid());
    }
    public record ReferralStats(long balanceKopecks,long totalEarnedKopecks,long totalSpentKopecks,
                                long invited,long activePaid) {}
}
