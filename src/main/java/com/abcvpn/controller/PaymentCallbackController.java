package com.abcvpn.controller;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.abcvpn.dto.PlategaCallback;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;
import com.abcvpn.service.PaymentService;
import com.abcvpn.telegram.TelegramClient;

@RestController
@Tag(name="Payments",description="Callback разовых платежей Platega")
public class PaymentCallbackController {
    private final PaymentService paymentService;
    private final TelegramClient telegramClient;
    public PaymentCallbackController(PaymentService paymentService,TelegramClient telegramClient) {
        this.paymentService=paymentService;this.telegramClient=telegramClient;
    }
    @PostMapping("/payment/callback")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary="Принять callback Platega",description="Проверяет credentials, сумму и валюту. CONFIRMED идемпотентно продлевает подписку только один раз; VPN синхронизируется отдельно.")
    @ApiResponses({@ApiResponse(responseCode="200",description="Callback обработан"),@ApiResponse(responseCode="400",description="Некорректные данные callback"),@ApiResponse(responseCode="403",description="Неверные credentials")})
    public Map<String,String> callback(@Parameter(description="Merchant ID Platega",required=true) @RequestHeader("X-MerchantId") String merchantId,
                                       @Parameter(description="Секрет callback Platega",required=true) @RequestHeader("X-Secret") String secret,
                                       @Valid @RequestBody PlategaCallback callback) {
        if(!paymentService.validCredentials(merchantId,secret)) throw new FlowException(ErrorCode.ACCESS_DENIED);
        PaymentService.CallbackResult result=paymentService.processCallback(callback);
        if(result.notifyUser()) {
            telegramClient.sendSilently(result.telegramId(),"✅ Оплата подтверждена. Подписка продлена.",null);
            telegramClient.notifyAdmin("💳 <b>Новая покупка VPN</b>\n\nID: <code>"+result.telegramId()+
                    "</code>\nСумма: <b>"+callback.amount().stripTrailingZeros().toPlainString()+" ₽</b>");
        }
        return Map.of("status","ok");
    }
}
