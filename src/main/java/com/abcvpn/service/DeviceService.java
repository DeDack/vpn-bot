package com.abcvpn.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.abcvpn.entity.Device;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.enumeration.DeviceStatus;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.remnawave.RemnawaveClient;
import com.abcvpn.repository.DeviceRepository;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.telegram.TelegramClient;

@Service
public class DeviceService {
    private static final Logger log=LoggerFactory.getLogger(DeviceService.class);
    private final DeviceRepository deviceRepository;private final SubscriptionRepository subscriptionRepository;
    private final TariffRepository tariffRepository;private final VpnAccessService vpnAccessService;
    private final TelegramClient telegramClient;private final Clock clock;

    public DeviceService(DeviceRepository deviceRepository,SubscriptionRepository subscriptionRepository,
                         TariffRepository tariffRepository,VpnAccessService vpnAccessService,
                         TelegramClient telegramClient,Clock clock) {
        this.deviceRepository=deviceRepository;this.subscriptionRepository=subscriptionRepository;
        this.tariffRepository=tariffRepository;this.vpnAccessService=vpnAccessService;
        this.telegramClient=telegramClient;this.clock=clock;
    }

    public DeviceOverview overview(long userId) {
        Subscription subscription=requireSubscription(userId);Tariff tariff=requireTariff(subscription);
        reconcile(subscription);
        List<Device> devices=deviceRepository.findByUserId(userId);
        return new DeviceOverview(devices,(int)devices.stream().filter(d->d.status()==DeviceStatus.ACTIVE).count(),tariff.deviceLimit());
    }

    @Transactional
    public AccessDecision access(String token,String hwid,String model,String os,String osVersion) {
        Subscription found=subscriptionRepository.findByToken(token).orElseThrow(()->new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        Subscription subscription=requireSubscriptionForUpdate(found.telegramId());
        if(!subscription.active(clock.instant())) return new AccessDecision(subscription,AccessStatus.EXPIRED);
        Device known=deviceRepository.findByUserIdAndHwid(subscription.telegramId(),hwid).orElse(null);
        if(known!=null) {
            Device updated=deviceRepository.upsertSeen(subscription.telegramId(),hwid,model,os,osVersion,known.status(),clock.instant());
            return new AccessDecision(subscription,accessStatus(updated.status()));
        }
        Tariff tariff=requireTariff(subscription);
        DeviceStatus status=deviceRepository.countActive(subscription.telegramId())<tariff.deviceLimit()?DeviceStatus.ACTIVE:DeviceStatus.BLOCKED;
        Device created=deviceRepository.upsertSeen(subscription.telegramId(),hwid,model,os,osVersion,status,clock.instant());
        if(status==DeviceStatus.ACTIVE) vpnAccessService.addDevice(subscription,hwid,os,osVersion,model);
        notifyNew(created,deviceRepository.countActive(subscription.telegramId()),tariff.deviceLimit());
        return new AccessDecision(subscription,accessStatus(status));
    }

    @Transactional
    public void register(long userId,String hwid,String model,String os,String osVersion) {
        Subscription subscription=requireSubscriptionForUpdate(userId);
        Device known=deviceRepository.findByUserIdAndHwid(userId,hwid).orElse(null);
        if(known!=null) {
            deviceRepository.upsertSeen(userId,hwid,model,os,osVersion,known.status(),clock.instant());
            if(known.status()==DeviceStatus.BLOCKED) vpnAccessService.removeDevice(subscription,hwid);
            return;
        }
        log.warn("[REMNAWAVE] Webhook contains unknown HWID for user {}",userId);
    }

    @Transactional
    public void block(long userId,long deviceId) {
        Subscription subscription=requireSubscriptionForUpdate(userId);Device device=requireDevice(userId,deviceId);
        if(device.status()==DeviceStatus.BLOCKED) return;
        vpnAccessService.removeDevice(subscription,device.hwid());
        deviceRepository.setStatus(device.id(),userId,DeviceStatus.BLOCKED);
    }

    @Transactional
    public boolean activate(long userId,long deviceId) {
        Subscription subscription=requireSubscriptionForUpdate(userId);Device device=requireDevice(userId,deviceId);
        if(device.status()==DeviceStatus.ACTIVE) return true;
        if(deviceRepository.countActive(userId)>=requireTariff(subscription).deviceLimit()) return false;
        vpnAccessService.addDevice(subscription,device.hwid(),device.deviceOs(),device.osVersion(),device.deviceModel());
        deviceRepository.setStatus(device.id(),userId,DeviceStatus.ACTIVE);
        return true;
    }

    @Transactional
    public void replace(long userId,long blockedDeviceId,long activeDeviceId) {
        Subscription subscription=requireSubscriptionForUpdate(userId);
        Device replacement=requireDevice(userId,blockedDeviceId);Device old=requireDevice(userId,activeDeviceId);
        if(replacement.status()==DeviceStatus.ACTIVE) return;
        if(old.status()!=DeviceStatus.ACTIVE) throw new FlowException(ErrorCode.INVALID_REQUEST,"Устройство уже отключено");
        vpnAccessService.addDevice(subscription,replacement.hwid(),replacement.deviceOs(),replacement.osVersion(),replacement.deviceModel());
        try { vpnAccessService.removeDevice(subscription,old.hwid()); }
        catch(RuntimeException exception) {
            try { vpnAccessService.removeDevice(subscription,replacement.hwid()); }
            catch(RuntimeException rollbackException) { exception.addSuppressed(rollbackException); }
            throw exception;
        }
        deviceRepository.setStatus(old.id(),userId,DeviceStatus.BLOCKED);
        deviceRepository.setStatus(replacement.id(),userId,DeviceStatus.ACTIVE);
    }

    @Transactional
    public void reset(long userId) {
        Subscription subscription=requireSubscriptionForUpdate(userId);
        vpnAccessService.removeAllDevices(subscription);deviceRepository.deleteAll(userId);
    }

    private void reconcile(Subscription subscription) {
        if(subscription.remnawaveUserId()==null) return;
        Map<String,RemnawaveClient.RemoteDevice> remote=new HashMap<>();
        for(var device:vpnAccessService.devices(subscription)) remote.put(device.hwid(),device);
        for(Device device:deviceRepository.findByUserId(subscription.telegramId())) {
            var remoteDevice=remote.remove(device.hwid());
            if(device.status()==DeviceStatus.ACTIVE&&remoteDevice==null) {
                log.warn("[REMNAWAVE] Repairing missing ACTIVE HWID for user {}",subscription.telegramId());
                vpnAccessService.addDevice(subscription,device.hwid(),device.deviceOs(),device.osVersion(),device.deviceModel());
            } else if(device.status()==DeviceStatus.BLOCKED&&remoteDevice!=null) {
                log.warn("[REMNAWAVE] Removing remote BLOCKED HWID for user {}",subscription.telegramId());
                vpnAccessService.removeDevice(subscription,device.hwid());
            } else if(remoteDevice!=null) {
                deviceRepository.upsertSeen(subscription.telegramId(),device.hwid(),remoteDevice.deviceModel(),
                        remoteDevice.platform(),remoteDevice.osVersion(),device.status(),clock.instant());
            }
        }
        for(String hwid:remote.keySet()) log.warn("[REMNAWAVE] Unknown remote HWID {} for user {}",hwid,subscription.telegramId());
    }

    private Subscription requireSubscription(long userId) {return subscriptionRepository.findByTelegramId(userId).orElseThrow(()->new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND));}
    private Subscription requireSubscriptionForUpdate(long userId) {return subscriptionRepository.findByTelegramIdForUpdate(userId).orElseThrow(()->new FlowException(ErrorCode.SUBSCRIPTION_NOT_FOUND));}
    private Tariff requireTariff(Subscription subscription) {return tariffRepository.findById(subscription.tariffId()).orElseThrow(()->new FlowException(ErrorCode.TARIFF_NOT_FOUND));}
    private Device requireDevice(long userId,long deviceId) {return deviceRepository.findByIdAndUserId(deviceId,userId).orElseThrow(()->new FlowException(ErrorCode.DEVICE_NOT_FOUND));}
    private void notifyNew(Device device,int activeCount,int limit) {
        String text="📱 <b>Новое устройство</b>\n\n"+value(device.deviceModel())+"\n"+value(device.deviceOs())+
                (device.osVersion()==null?"":" "+device.osVersion())+"\nСтатус: <b>"+device.status()+"</b>\nАктивно: "+activeCount+" из "+limit;
        telegramClient.sendSilently(device.userId(),text,null);
    }
    private String value(String value) {return value==null||value.isBlank()?"Неизвестно":value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    public record DeviceOverview(List<Device> devices,int activeCount,int deviceLimit) {}
    private AccessStatus accessStatus(DeviceStatus status) {
        return status==DeviceStatus.ACTIVE?AccessStatus.ACTIVE:AccessStatus.BLOCKED;
    }

    public enum AccessStatus { ACTIVE, BLOCKED, EXPIRED }
    public record AccessDecision(Subscription subscription,AccessStatus status) {}
}
