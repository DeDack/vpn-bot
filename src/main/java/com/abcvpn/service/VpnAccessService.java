package com.abcvpn.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.remnawave.RemnawaveClient;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;

@Service
public class VpnAccessService {
    private final RemnawaveClient remnawaveClient;
    private final TariffRepository tariffRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final Clock clock;
    public VpnAccessService(RemnawaveClient remnawaveClient,TariffRepository tariffRepository,
                            SubscriptionRepository subscriptionRepository,Clock clock) {
        this.remnawaveClient=remnawaveClient;this.tariffRepository=tariffRepository;
        this.subscriptionRepository=subscriptionRepository;this.clock=clock;
    }

    public void sync(Subscription subscription) {
        boolean active=subscription.active(clock.instant());
        if(subscription.remnawaveUserId()==null) {
            if(!active) { subscriptionRepository.markSynced(subscription.telegramId()); return; }
            Tariff tariff=tariffRepository.findById(subscription.tariffId()).orElseThrow();
            RemnawaveClient.RemoteUser remote=remnawaveClient.findByUsername("tg_"+subscription.telegramId());
            if(remote==null) remote=remnawaveClient.create(subscription.telegramId(),requiredExpiry(subscription),tariff.remnawaveSquadId(),tariff.deviceLimit());
            else {
                remote=remnawaveClient.update(remote.id(),requiredExpiry(subscription),tariff.remnawaveSquadId(),tariff.deviceLimit());
                if(!remote.enabled()) remnawaveClient.enable(remote.id());
            }
            subscriptionRepository.markProvisioned(subscription.telegramId(),remote.id(),remote.subscriptionUrl());
            return;
        }
        if(active) {
            Tariff tariff=tariffRepository.findById(subscription.tariffId()).orElseThrow();
            RemnawaveClient.RemoteUser remote=remnawaveClient.update(subscription.remnawaveUserId(),requiredExpiry(subscription),tariff.remnawaveSquadId(),tariff.deviceLimit());
            if(!remote.enabled()) remnawaveClient.enable(subscription.remnawaveUserId());
        } else remnawaveClient.disable(subscription.remnawaveUserId());
        subscriptionRepository.markSynced(subscription.telegramId());
    }

    public boolean differs(Subscription subscription) {
        if(subscription.remnawaveUserId()==null) return subscription.active(clock.instant());
        boolean remoteEnabled=remnawaveClient.get(subscription.remnawaveUserId()).enabled();
        return remoteEnabled!=subscription.active(clock.instant());
    }
    public void addDevice(Subscription subscription,String hwid,String os,String osVersion,String model) {
        remnawaveClient.addHwid(requiredRemoteId(subscription),hwid,os,osVersion,model);
    }
    public void removeDevice(Subscription subscription,String hwid) {
        remnawaveClient.deleteHwid(requiredRemoteId(subscription),hwid);
    }
    public void removeAllDevices(Subscription subscription) {
        remnawaveClient.deleteAllHwid(requiredRemoteId(subscription));
    }
    public List<RemnawaveClient.RemoteDevice> devices(Subscription subscription) {
        return remnawaveClient.getHwidDevices(requiredRemoteId(subscription));
    }
    public void deleteUser(Subscription subscription) {
        if(subscription.remnawaveUserId()!=null) remnawaveClient.delete(subscription.remnawaveUserId());
    }
    private String requiredRemoteId(Subscription subscription) {
        if(subscription.remnawaveUserId()==null) throw new IllegalStateException("Пользователь ещё не синхронизирован с Remnawave");
        return subscription.remnawaveUserId();
    }
    private Instant requiredExpiry(Subscription subscription) {
        return subscription.expiresAt()==null?Instant.parse("2099-12-31T23:59:59Z"):subscription.expiresAt();
    }
}
