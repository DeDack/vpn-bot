package com.abcvpn.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Campaign;
import com.abcvpn.entity.User;
import com.abcvpn.enumeration.SourceType;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.CampaignRepository;
import com.abcvpn.repository.UserRepository;
import com.abcvpn.telegram.TelegramClient;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final CampaignRepository campaignRepository;
    private final TelegramClient telegramClient;
    private final AnalyticsEventService analyticsEventService;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public UserService(UserRepository userRepository, CampaignRepository campaignRepository,
                       TelegramClient telegramClient,AnalyticsEventService analyticsEventService, Clock clock) {
        this.userRepository = userRepository;
        this.campaignRepository = campaignRepository;
        this.telegramClient = telegramClient;
        this.analyticsEventService=analyticsEventService;
        this.clock = clock;
    }

    public User registerOrUpdate(long telegramId, String username, String firstName, String startParameter) {
        return registerOrUpdateWithStatus(telegramId,username,firstName,startParameter).user();
    }

    public Registration registerOrUpdateWithStatus(long telegramId,String username,String firstName,String startParameter) {
        return register(telegramId,username,firstName,startParameter);
    }

    @Transactional
    public Registration registerStart(long telegramId,String username,String firstName,String startParameter) {
        Registration registration=register(telegramId,username,firstName,startParameter);
        analyticsEventService.record(telegramId,AnalyticsEventService.BOT_START,registration.user().createdAt());
        return registration;
    }

    private Registration register(long telegramId,String username,String firstName,String startParameter) {
        Instant now = clock.instant();
        var existing = userRepository.findById(telegramId);
        if (existing.isPresent()) {
            userRepository.updateActivity(telegramId, username, firstName, now);
            return new Registration(userRepository.findById(telegramId).orElseThrow(),false);
        }
        Attribution attribution = attribution(telegramId, startParameter);
        User created=userRepository.create(telegramId, username, firstName, attribution.sourceType(),
                attribution.campaignId(), attribution.referrerId(), newReferralCode(), now);
        telegramClient.notifyAdmin("👤 <b>Новый пользователь</b>\n\nID: <code>"+telegramId+"</code>\nUsername: "+
                username(username)+"\nИмя: "+text(firstName)+"\nИсточник: <b>"+attribution.sourceType()+"</b>");
        return new Registration(created,true);
    }

    public User requireActive(long telegramId) {
        User user = userRepository.findById(telegramId).orElseThrow(() -> new FlowException(ErrorCode.USER_NOT_FOUND));
        if (user.banned()) throw new FlowException(ErrorCode.USER_BANNED);
        return user;
    }

    private Attribution attribution(long telegramId, String parameter) {
        if (parameter == null || parameter.isBlank()) return new Attribution(SourceType.ORGANIC, null, null);
        if (parameter.startsWith("ad_")) {
            Campaign campaign = campaignRepository.findEnabledByCode(parameter.substring(3)).orElse(null);
            if (campaign != null) return new Attribution(SourceType.CAMPAIGN, campaign.id(), null);
        }
        if (parameter.startsWith("ref_")) {
            User referrer = userRepository.findByReferralCode(parameter.substring(4)).orElse(null);
            if (referrer != null && referrer.telegramId() != telegramId)
                return new Attribution(SourceType.REFERRAL, null, referrer.telegramId());
        }
        return new Attribution(SourceType.ORGANIC, null, null);
    }

    private String newReferralCode() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
    private String username(String value) {return value==null||value.isBlank()?"—":"@"+text(value);}
    private String text(String value) {return value==null||value.isBlank()?"—":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}
    private record Attribution(SourceType sourceType, Long campaignId, Long referrerId) {}
    public record Registration(User user,boolean created) {}
}
