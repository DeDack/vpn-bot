package com.abcvpn.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import com.abcvpn.config.AbcVpnProperties;
import com.abcvpn.config.PlategaProperties;
import com.abcvpn.exception.ErrorCode;
import com.abcvpn.exception.FlowException;

@Component
public class PlategaClient {
    private static final Logger log=LoggerFactory.getLogger(PlategaClient.class);
    private final RestClient client;
    private final PlategaProperties properties;
    private final AbcVpnProperties abcVpnProperties;

    public PlategaClient(RestClient.Builder builder,PlategaProperties properties,AbcVpnProperties abcVpnProperties) {
        this.properties=properties;
        this.abcVpnProperties=abcVpnProperties;
        if(properties.enabled()&&(blank(properties.merchantId())||blank(properties.secret())))
            throw new IllegalStateException("PLATEGA_MERCHANT_ID and PLATEGA_SECRET are required when PAYMENT_ENABLED=true");
        if(properties.enabled()&&(abcVpnProperties.publicBaseUrl()==null||!abcVpnProperties.publicBaseUrl().startsWith("https://")))
            throw new IllegalStateException("PUBLIC_BASE_URL must be a public HTTPS URL when PAYMENT_ENABLED=true");
        this.client=builder.baseUrl(properties.baseUrl()).build();
    }

    public CreatedPayment create(BigDecimal amount,long telegramId,String username,String description,UUID localPaymentId) {
        Map<String,Object> metadata=new java.util.LinkedHashMap<>();
        metadata.put("userId",Long.toString(telegramId));
        if(username!=null&&!username.isBlank()) metadata.put("userName",username);
        Map<String,Object> request=new java.util.LinkedHashMap<>();
        request.put("paymentDetails",Map.of("amount",amount,"currency","RUB"));
        request.put("description",description);
        request.put("return",abcVpnProperties.publicBaseUrl()+"/payment/success");
        request.put("failedUrl",abcVpnProperties.publicBaseUrl()+"/payment/failed");
        request.put("payload",localPaymentId.toString());
        request.put("metadata",metadata);
        try {
            Response response=client.post().uri("/v2/transaction/process")
                    .headers(this::authorize).body(request).retrieve()
                    .onStatus(HttpStatusCode::isError,(req,res)->{throw httpError(res.getStatusCode().value());})
                    .body(Response.class);
            if (response==null || response.transactionId()==null || response.paymentUrl()==null)
                throw new PlategaException(true,"Platega вернула неполный ответ");
            if(!"PENDING".equals(response.status())) throw new PlategaException(true,"Неожиданный статус создания Platega");
            Duration duration=parseDuration(response.expiresIn());
            log.info("[PLATEGA] Created transaction {} for payment {}",response.transactionId(),localPaymentId);
            return new CreatedPayment(response.transactionId(),response.paymentUrl(),Instant.now().plus(duration));
        } catch (PlategaException exception) {
            throw exception;
        } catch (RestClientException exception) {
            log.warn("[PLATEGA] Payment creation outcome is uncertain for payment {}",localPaymentId);
            throw new PlategaException(true,"Не удалось однозначно определить результат создания платежа",exception);
        }
    }

    public TransactionStatus getStatus(UUID transactionId) {
        try {
            StatusResponse response=client.get().uri("/transaction/{id}",transactionId).headers(this::authorize)
                    .retrieve().onStatus(HttpStatusCode::isError,(req,res)->{throw httpError(res.getStatusCode().value());})
                    .body(StatusResponse.class);
            if(response==null||response.id()==null||response.paymentDetails()==null)
                throw new PlategaException(false,"Platega вернула неполный статус транзакции");
            log.info("[PLATEGA] Transaction status {} is {}",transactionId,response.status());
            return new TransactionStatus(response.id(),response.status(),response.paymentDetails().amount(),
                    response.paymentDetails().currency(),response.paymentMethod(),response.payload());
        } catch(PlategaException exception) { throw exception; }
        catch(RestClientException exception) { throw new PlategaException(true,"Не удалось получить статус Platega",exception); }
    }

    private Duration parseDuration(String value) {
        if(value==null||value.isBlank()) return Duration.ofMinutes(15);
        String[] parts=value.split(":");
        if(parts.length!=3) return Duration.ofMinutes(15);
        return Duration.ofHours(Long.parseLong(parts[0])).plusMinutes(Long.parseLong(parts[1])).plusSeconds(Long.parseLong(parts[2]));
    }
    private void authorize(org.springframework.http.HttpHeaders headers) {
        headers.set("X-MerchantId",properties.merchantId());headers.set("X-Secret",properties.secret());
    }
    private PlategaException httpError(int status) {
        if(status==400) return new PlategaException(false,"Platega отклонила запрос");
        if(status==401||status==403) return new PlategaException(false,"Platega отклонила авторизацию");
        if(status==404) return new PlategaException(false,"Транзакция Platega не найдена");
        return new PlategaException(true,"Временная ошибка Platega");
    }
    private boolean blank(String value) { return value==null||value.isBlank(); }
    public record CreatedPayment(UUID transactionId,String url,Instant expiresAt) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record Response(UUID transactionId,String status,String url,String redirect,String expiresIn) {
        private String paymentUrl() { return url != null ? url : redirect; }
    }
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record StatusResponse(UUID id,String status,PaymentDetails paymentDetails,String mechantId,
                                  BigDecimal comission,String paymentMethod,String payload) {}
    @JsonIgnoreProperties(ignoreUnknown=true)
    private record PaymentDetails(BigDecimal amount,String currency) {}
    public record TransactionStatus(UUID id,String status,BigDecimal amount,String currency,String paymentMethod,String payload) {}
    public static class PlategaException extends RuntimeException {
        private final boolean uncertain;
        public PlategaException(boolean uncertain,String message){super(message);this.uncertain=uncertain;}
        public PlategaException(boolean uncertain,String message,Throwable cause){super(message,cause);this.uncertain=uncertain;}
        public boolean uncertain(){return uncertain;}
    }
}
