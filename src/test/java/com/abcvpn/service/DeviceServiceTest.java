package com.abcvpn.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.abcvpn.entity.Device;
import com.abcvpn.entity.Subscription;
import com.abcvpn.entity.Tariff;
import com.abcvpn.enumeration.DeviceStatus;
import com.abcvpn.enumeration.SyncStatus;
import com.abcvpn.repository.DeviceRepository;
import com.abcvpn.repository.SubscriptionRepository;
import com.abcvpn.repository.TariffRepository;
import com.abcvpn.remnawave.RemnawaveClient;
import com.abcvpn.telegram.TelegramClient;

class DeviceServiceTest {
    private final DeviceRepository devices=mock(DeviceRepository.class);
    private final SubscriptionRepository subscriptions=mock(SubscriptionRepository.class);
    private final TariffRepository tariffs=mock(TariffRepository.class);
    private final VpnAccessService vpn=mock(VpnAccessService.class);
    private final TelegramClient telegram=mock(TelegramClient.class);
    private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    private final Subscription subscription=new Subscription(10,2,null,false,"42","url",SyncStatus.SYNCED,0,null,null,now,now);
    private DeviceService service;

    @BeforeEach
    void setUp() {
        service=new DeviceService(devices,subscriptions,tariffs,vpn,telegram,Clock.fixed(now,ZoneOffset.UTC));
        when(subscriptions.findByTelegramIdForUpdate(10)).thenReturn(Optional.of(subscription));
        when(subscriptions.findByTelegramId(10)).thenReturn(Optional.of(subscription));
        when(subscriptions.findByToken("token12345")).thenReturn(Optional.of(subscription));
        when(vpn.devices(subscription)).thenReturn(List.of());
        when(tariffs.findById(2)).thenReturn(Optional.of(new Tariff(2,"BASE","Base",30,BigDecimal.TEN,1,null,UUID.randomUUID(),true,0)));
    }

    @Test
    void newDeviceOverLimitIsBlockedByProxyAndKeptLocally() {
        Device blocked=device(2,"new-hwid-123",DeviceStatus.BLOCKED);
        when(devices.findByUserIdAndHwid(10,"new-hwid-123")).thenReturn(Optional.empty());
        when(devices.countActive(10)).thenReturn(1);
        when(devices.upsertSeen(eq(10L),eq("new-hwid-123"),any(),any(),any(),eq(DeviceStatus.BLOCKED),eq(now))).thenReturn(blocked);

        DeviceService.AccessDecision result=service.access("token12345","new-hwid-123","iPad","iOS","18");

        org.junit.jupiter.api.Assertions.assertEquals(DeviceService.AccessStatus.BLOCKED,result.status());
        verify(vpn,never()).addDevice(any(),any(),any(),any(),any());
        verify(telegram).sendSilently(eq(10L),any(),eq(null));
    }

    @Test
    void expiredSubscriptionDoesNotRegisterDevice() {
        Subscription expired=new Subscription(10,2,now.minusSeconds(1),false,"42","url",SyncStatus.SYNCED,0,null,null,now,now);
        when(subscriptions.findByToken("expired12")).thenReturn(Optional.of(expired));
        when(subscriptions.findByTelegramIdForUpdate(10)).thenReturn(Optional.of(expired));

        DeviceService.AccessDecision result=service.access("expired12","new-hwid-123","Phone","Android","15");

        org.junit.jupiter.api.Assertions.assertEquals(DeviceService.AccessStatus.EXPIRED,result.status());
        verify(devices,never()).upsertSeen(any(Long.class),any(),any(),any(),any(),any(),any());
        verify(vpn,never()).addDevice(any(),any(),any(),any(),any());
    }

    @Test
    void knownDeviceDoesNotNotifyAgain() {
        Device known=device(1,"known-hwid-1",DeviceStatus.ACTIVE);
        when(devices.findByUserIdAndHwid(10,"known-hwid-1")).thenReturn(Optional.of(known));

        service.register(10,"known-hwid-1","Phone","iOS","18");

        verify(telegram,never()).sendSilently(any(Long.class),any(),any());
    }

    @Test
    void activationAtLimitRequestsReplacement() {
        Device blocked=device(2,"blocked-hwid",DeviceStatus.BLOCKED);
        when(devices.findByIdAndUserId(2,10)).thenReturn(Optional.of(blocked));
        when(devices.countActive(10)).thenReturn(1);

        assertFalse(service.activate(10,2));
        verify(vpn,never()).addDevice(any(),any(),any(),any(),any());
    }

    @Test
    void failedOldDeviceRemovalRollsBackNewDevice() {
        Device replacement=device(2,"new-hwid-123",DeviceStatus.BLOCKED);Device old=device(1,"old-hwid-123",DeviceStatus.ACTIVE);
        when(devices.findByIdAndUserId(2,10)).thenReturn(Optional.of(replacement));
        when(devices.findByIdAndUserId(1,10)).thenReturn(Optional.of(old));
        doThrow(new RuntimeException("down")).when(vpn).removeDevice(subscription,"old-hwid-123");

        assertThrows(RuntimeException.class,()->service.replace(10,2,1));

        verify(vpn).addDevice(subscription,"new-hwid-123","iOS","18","iPad");
        verify(vpn).removeDevice(subscription,"new-hwid-123");
        verify(devices,never()).setStatus(any(Long.class),any(Long.class),any());
    }

    @Test
    void overviewDoesNotImportUnknownRemoteDevice() {
        when(vpn.devices(subscription)).thenReturn(List.of(new RemnawaveClient.RemoteDevice("remote-hwid-123","Phone","Android","15")));
        when(devices.findByUserId(10)).thenReturn(List.of());

        DeviceService.DeviceOverview overview=service.overview(10);

        org.junit.jupiter.api.Assertions.assertEquals(0,overview.activeCount());
        verify(devices,never()).upsertSeen(any(Long.class),any(),any(),any(),any(),any(),any());
        verify(telegram,never()).sendSilently(any(Long.class),any(),any());
    }

    private Device device(long id,String hwid,DeviceStatus status) {
        return new Device(id,10,hwid,"iPad","iOS","18",status,now,now);
    }
}
