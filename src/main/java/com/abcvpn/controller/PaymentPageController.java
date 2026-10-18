package com.abcvpn.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import com.abcvpn.config.AbcVpnProperties;

@Hidden
@Controller
public class PaymentPageController {
    private final AbcVpnProperties properties;

    public PaymentPageController(AbcVpnProperties properties) {
        this.properties = properties;
    }

    @ResponseBody
    @GetMapping(value="/payment/success",produces=MediaType.TEXT_HTML_VALUE)
    public String success() { return "<html><body><h1>Оплата обрабатывается</h1><p>Вернитесь в Telegram. Доступ появится после подтверждения платежа.</p></body></html>"; }
    @ResponseBody
    @GetMapping("/payment/failed")
    public ResponseEntity<Void> failed() {
        String username=properties.telegram().botUsername().replace("@","");
        return ResponseEntity.status(302).location(URI.create("https://t.me/"+username+"?start=payment_failed")).build();
    }
}
