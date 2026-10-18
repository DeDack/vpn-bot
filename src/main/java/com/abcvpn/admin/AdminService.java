package com.abcvpn.admin;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Tariff;
import com.abcvpn.entity.User;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.repository.UserRepository;
import com.abcvpn.repository.PaymentRepository;
import com.abcvpn.repository.CampaignRepository;
import com.abcvpn.repository.AnalyticsRepository;
import com.abcvpn.service.SubscriptionService;
import com.abcvpn.service.PaymentService;
import com.abcvpn.service.DeviceService;
import com.abcvpn.service.VpnAccessService;
import com.abcvpn.service.ReferralService;
import com.abcvpn.telegram.TelegramClient;

@Service
public class AdminService {
    private final UserRepository userRepository;private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;private final SubscriptionService subscriptionService;
    private final PaymentRepository paymentRepository;private final CampaignRepository campaignRepository;
    private final TelegramClient telegramClient;private final PaymentService paymentService;private final DeviceService deviceService;
    private final VpnAccessService vpnAccessService;
    private final AnalyticsRepository analyticsRepository;
    private final ReferralService referralService;
    public AdminService(UserRepository userRepository,SubscriptionRepository subscriptionRepository,
                        TariffRepository tariffRepository,SubscriptionService subscriptionService,
                        PaymentRepository paymentRepository,CampaignRepository campaignRepository,
                        TelegramClient telegramClient,PaymentService paymentService,DeviceService deviceService,
                        VpnAccessService vpnAccessService,AnalyticsRepository analyticsRepository,ReferralService referralService) {
        this.userRepository=userRepository;this.subscriptionRepository=subscriptionRepository;
        this.tariffRepository=tariffRepository;this.subscriptionService=subscriptionService;
        this.paymentRepository=paymentRepository;this.campaignRepository=campaignRepository;
        this.telegramClient=telegramClient;this.paymentService=paymentService;this.deviceService=deviceService;
        this.vpnAccessService=vpnAccessService;
        this.analyticsRepository=analyticsRepository;
        this.referralService=referralService;
    }
    public Dashboard dashboard(String query) {
        List<User> users=userRepository.search(query,200);Map<Long,Object> subscriptions=new LinkedHashMap<>();
        Map<Long,Object> devices=new LinkedHashMap<>();Map<Long,Object> referrals=new LinkedHashMap<>();
        for(User user:users) {
            var subscription=subscriptionRepository.findByTelegramId(user.telegramId()).orElse(null);subscriptions.put(user.telegramId(),subscription);
            if(subscription!=null) devices.put(user.telegramId(),deviceService.overview(user.telegramId()));
            referrals.put(user.telegramId(),referralService.stats(user.telegramId()));
        }
        var campaigns=campaignRepository.findAll();Map<Long,Object> stats=new LinkedHashMap<>();
        for(var campaign:campaigns) stats.put(campaign.id(),campaignRepository.stats(campaign.id()));
        return new Dashboard(users,subscriptions,devices,referrals,tariffRepository.findAll(),paymentRepository.findRecent(200),campaigns,stats,subscriptionRepository.findErrors(200));
    }
    public Analytics analytics() {return analytics("all");}
    public Analytics analytics(String period) {
        java.time.Instant cutoff=cutoff(period);var summary=analyticsRepository.summary(cutoff);
        return new Analytics(analyticsRepository.campaigns(cutoff),analyticsRepository.users(cutoff),summary,period,
                percent(summary.happ(),summary.starts()),percent(summary.vpn(),summary.happ()),percent(summary.paid(),summary.vpn()));
    }
    @Transactional public void ban(long telegramId) {requireUser(telegramId);userRepository.setBanned(telegramId,true);subscriptionRepository.revoke(telegramId);}
    public void unban(long telegramId) {requireUser(telegramId);userRepository.setBanned(telegramId,false);}
    @Transactional public void grant(long telegramId,long tariffId,Integer days) {
        requireUser(telegramId);Tariff tariff=tariffRepository.findById(tariffId).orElseThrow(()->new FlowException(ErrorCode.TARIFF_NOT_FOUND));
        subscriptionService.grant(telegramId,tariff.id(),days==null?tariff.durationDays():days);
    }
    @Transactional public void extend(long telegramId,int days) {
        var subscription=subscriptionRepository.findByTelegramIdForUpdate(telegramId).orElseThrow(()->new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        subscriptionService.extend(telegramId,subscription.tariffId(),days);
    }
    public void revoke(long telegramId) {subscriptionService.revoke(telegramId);}
    public void retry(long telegramId) {subscriptionRepository.markPending(telegramId);}
    public void sendMessage(long telegramId,String text) {requireUser(telegramId);telegramClient.sendSilently(telegramId,text,null);}
    public void saveTariff(Long id,String code,String name,int durationDays,BigDecimal priceRub,int deviceLimit,
                           Long trafficLimit,UUID squadId,boolean enabled,int sortOrder) {
        tariffRepository.save(id,code,name,durationDays,priceRub,deviceLimit,trafficLimit,squadId,enabled,sortOrder);
    }
    public void setTariffEnabled(long id,boolean enabled) {tariffRepository.setEnabled(id,enabled);}
    public void createCampaign(String code,String name,String comment) {campaignRepository.create(code,name,comment);}
    public void updateCampaign(long id,String name,String comment,boolean enabled) {campaignRepository.update(id,name,comment,enabled);}
    public void checkPayment(UUID paymentId) { paymentService.processCallback(paymentService.checkStatus(paymentId)); }
    public void activateDevice(long userId,long deviceId) {
        if(!deviceService.activate(userId,deviceId)) throw new FlowException(ErrorCode.INVALID_REQUEST,"Лимит занят — выполните замену устройства");
    }
    public void blockDevice(long userId,long deviceId) { deviceService.block(userId,deviceId); }
    public void replaceDevice(long userId,long blockedDeviceId,long activeDeviceId) { deviceService.replace(userId,blockedDeviceId,activeDeviceId); }
    public void resetDevices(long userId) { deviceService.reset(userId); }
    @Transactional
    public void deleteUser(long telegramId) {
        requireUser(telegramId);
        subscriptionRepository.findByTelegramId(telegramId).ifPresent(vpnAccessService::deleteUser);
        if(!userRepository.delete(telegramId)) throw new FlowException(ErrorCode.USER_NOT_FOUND);
    }
    private User requireUser(long id){return userRepository.findById(id).orElseThrow(()->new FlowException(ErrorCode.USER_NOT_FOUND));}
    public record Dashboard(List<User> users,Map<Long,Object> subscriptions,Map<Long,Object> devices,Map<Long,Object> referrals,List<Tariff> tariffs,
                            List<com.abcvpn.entity.Payment> payments,List<com.abcvpn.entity.Campaign> campaigns,
                            Map<Long,Object> campaignStats,List<com.abcvpn.entity.Subscription> syncErrors) {}
    private java.time.Instant cutoff(String period) {
        java.time.ZonedDateTime today=java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Moscow")).toLocalDate().atStartOfDay(java.time.ZoneId.of("Europe/Moscow"));
        return switch(period) {case "today"->today.toInstant();case "7d"->today.minusDays(6).toInstant();case "30d"->today.minusDays(29).toInstant();default->null;};
    }
    private double percent(long value,long total) {return total==0?0:Math.round(value*1000.0/total)/10.0;}
    public record Analytics(List<AnalyticsRepository.CampaignAnalytics> campaigns,List<AnalyticsRepository.UserAnalytics> users,
                            AnalyticsRepository.Summary summary,String period,double startToHapp,double happToVpn,double vpnToPaid) {}
}
