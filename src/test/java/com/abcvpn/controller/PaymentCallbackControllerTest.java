package com.abcvpn.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.abcvpn.dto.PlategaCallback;
import com.abcvpn.exception.FlowException;
import com.abcvpn.service.PaymentService;
import com.abcvpn.telegram.TelegramClient;

class PaymentCallbackControllerTest {
    @Test void rejectsInvalidCredentialsBeforeProcessingCallback() {
        PaymentService service=mock(PaymentService.class);TelegramClient telegram=mock(TelegramClient.class);
        when(service.validCredentials("bad","bad")).thenReturn(false);
        var controller=new PaymentCallbackController(service,telegram);
        var callback=new PlategaCallback(UUID.randomUUID(),new BigDecimal("199"),"RUB","CONFIRMED",2);
        assertThatThrownBy(()->controller.callback("bad","bad",callback)).isInstanceOf(FlowException.class);
        verify(service,never()).processCallback(any());
    }

    @Test void notifiesAdminOnlyForNewConfirmedPayment() {
        PaymentService service=mock(PaymentService.class);TelegramClient telegram=mock(TelegramClient.class);
        when(service.validCredentials("merchant","secret")).thenReturn(true);
        when(service.processCallback(any())).thenReturn(new PaymentService.CallbackResult(10,true,true));
        var controller=new PaymentCallbackController(service,telegram);
        var callback=new PlategaCallback(UUID.randomUUID(),new BigDecimal("405.00"),"RUB","CONFIRMED",2);

        controller.callback("merchant","secret",callback);

        verify(telegram).notifyAdmin(contains("405 ₽"));
    }
}
