package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import com.abcvpn.repository.BroadcastRepository;
import com.abcvpn.repository.MarketingPreferenceRepository;
import com.abcvpn.telegram.TelegramClient;

class BroadcastDeliveryServiceTest {
    private final BroadcastRepository repository=mock(BroadcastRepository.class);
    private final MarketingPreferenceRepository preferences=mock(MarketingPreferenceRepository.class);
    private final BroadcastService broadcasts=mock(BroadcastService.class);
    private final TelegramClient telegram=mock(TelegramClient.class);
    private final Instant now=Instant.parse("2026-10-11T10:00:00Z");
    private final BroadcastDeliveryService service=new BroadcastDeliveryService(repository,preferences,broadcasts,telegram,Clock.fixed(now,ZoneOffset.UTC));

    @Test void successfulDeliveryIsRecorded() {
        var delivery=delivery(1,true);
        when(telegram.send(10,"message",null)).thenReturn(55);

        service.deliverOne(delivery);

        verify(repository).markSent(1,55);
    }

    @Test void userWhoOptedOutAfterQueueIsSkipped() {
        service.deliverOne(delivery(1,false));

        verifyNoInteractions(telegram);
        verify(repository).markFailed(1,"Marketing disabled",true);
    }

    @Test void restartRecoveryClaimsAndDeliversPendingWork() {
        var delivery=delivery(1,true);
        when(repository.claim(20)).thenReturn(List.of(delivery));
        when(telegram.send(10,"message",null)).thenReturn(7);

        service.deliver();

        verify(repository).claim(20);
        verify(repository).markSent(1,7);
    }

    @Test void telegramRetryAfterHeaderIsRespected() {
        HttpHeaders headers=new HttpHeaders();headers.set("Retry-After","12");
        failWith429(headers,"{\"parameters\":{\"retry_after\":17}}");

        service.deliverOne(delivery(1,true));

        verify(repository).markRetry(1,now.plusSeconds(12),"Telegram HTTP 429");
    }

    @Test void telegramRetryAfterBodyIsRespected() {
        failWith429(new HttpHeaders(),"{\"parameters\":{\"retry_after\":17}}");
        service.deliverOne(delivery(1,true));
        verify(repository).markRetry(1,now.plusSeconds(17),"Telegram HTTP 429");
    }

    @Test void retryAfterHeaderHasPriorityOverBody() {
        HttpHeaders headers=new HttpHeaders();headers.set("Retry-After","12");
        failWith429(headers,"{\"parameters\":{\"retry_after\":17}}");
        service.deliverOne(delivery(1,true));
        verify(repository).markRetry(1,now.plusSeconds(12),"Telegram HTTP 429");
    }

    @Test void missingRetryAfterFallsBackToExponentialBackoff() {
        failWith429(new HttpHeaders(),"{\"ok\":false}");
        service.deliverOne(delivery(1,true));
        verify(repository).markRetry(1,now.plusSeconds(10),"Telegram HTTP 429");
    }

    @Test void invalidJsonFallsBackToExponentialBackoff() {
        failWith429(new HttpHeaders(),"not-json");
        assertThatCode(()->service.deliverOne(delivery(1,true))).doesNotThrowAnyException();
        verify(repository).markRetry(1,now.plusSeconds(10),"Telegram HTTP 429");
    }

    @Test void non429DoesNotUseRetryAfter() {
        HttpHeaders headers=new HttpHeaders();headers.set("Retry-After","30");
        when(telegram.send(10,"message",null)).thenThrow(HttpClientErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR,"error",headers,"{\"parameters\":{\"retry_after\":40}}".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8));
        service.deliverOne(delivery(1,true));
        verify(repository).markRetry(1,now.plusSeconds(10),"Telegram HTTP 500");
    }

    @Test void recoveredSendingDeliveryIsStillDelivered() {
        var delivery=new BroadcastRepository.Delivery(1,2,10,2,"message",null,null,null,true,true,true);
        when(telegram.send(10,"message",null)).thenReturn(8);
        service.deliverOne(delivery);
        verify(repository).markSent(1,8);
    }

    private void failWith429(HttpHeaders headers,String body) {
        when(telegram.send(10,"message",null)).thenThrow(HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS,"rate limit",headers,body.getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8));
    }

    private BroadcastRepository.Delivery delivery(int attempts,boolean eligible) {
        return new BroadcastRepository.Delivery(1,2,10,attempts,"message",null,null,null,true,false,eligible);
    }
}
