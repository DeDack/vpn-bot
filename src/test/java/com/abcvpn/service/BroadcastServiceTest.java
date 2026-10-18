package com.abcvpn.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import com.abcvpn.entity.Broadcast;
import com.abcvpn.enumeration.BroadcastSegment;
import com.abcvpn.exception.FlowException;
import com.abcvpn.repository.BroadcastRepository;
import com.abcvpn.telegram.TelegramClient;

class BroadcastServiceTest {
    private final BroadcastRepository repository=mock(BroadcastRepository.class);
    private final TelegramClient telegram=mock(TelegramClient.class);
    private final BroadcastService service=new BroadcastService(repository,telegram);

    @Test void returnBonusRequiresExactSegmentAndCallback() {
        assertThatThrownBy(()->service.preview("RETURN_BONUS","Получите два дня",null,null,null,true,
                Set.of(BroadcastSegment.EXPIRED_TRIAL_NO_VPN))).isInstanceOf(FlowException.class);
        assertThatThrownBy(()->service.preview("RETURN_BONUS","Получите два дня","Активировать","CALLBACK","return_bonus",true,
                Set.of(BroadcastSegment.ALL))).isInstanceOf(FlowException.class);
        verifyNoInteractions(repository);
    }

    @Test void confirmationLocksDraftAndQueuesDeduplicatedAudience() {
        Broadcast draft=broadcast("DRAFT");
        when(repository.findForUpdate(3)).thenReturn(draft);
        when(repository.segments(3)).thenReturn(java.util.List.of(BroadcastSegment.ALL));
        when(repository.queue(3,Set.of(BroadcastSegment.ALL),true,false)).thenReturn(12);

        service.confirm(3);

        verify(repository).findForUpdate(3);
        verify(repository).queue(3,Set.of(BroadcastSegment.ALL),true,false);
    }

    @Test void personalSubscriptionLinksAreRejected() {
        assertThatThrownBy(()->service.preview("SERVICE_UPDATE","https://sub.abc-vpn.example.com/secret",null,null,null,false,
                Set.of(BroadcastSegment.ALL))).isInstanceOf(FlowException.class);
        verifyNoInteractions(repository);
    }

    @Test void marketingMessageDoesNotContainOptOutButton() {
        var delivery=new BroadcastRepository.Delivery(1,3,10,1,"text","Подключить","CALLBACK","connect",true,false,true);

        TelegramClient.Keyboard keyboard=service.keyboard(delivery);

        assertThat(keyboard.inline_keyboard()).singleElement().satisfies(row->{
            assertThat(row.buttons()).singleElement().satisfies(button->assertThat(button.callback_data()).isEqualTo("connect"));
        });
    }

    private Broadcast broadcast(String status) {
        return new Broadcast(3,"SERVICE_UPDATE","text",null,null,null,true,status,0,0,0,Instant.now(),null,null);
    }
}
