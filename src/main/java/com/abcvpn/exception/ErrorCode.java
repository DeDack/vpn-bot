package com.abcvpn.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "Пользователь не найден"),
    USER_BANNED(HttpStatus.FORBIDDEN, "Доступ заблокирован"),
    TARIFF_NOT_FOUND(HttpStatus.NOT_FOUND, "Тариф не найден"),
    TRIAL_ALREADY_USED(HttpStatus.CONFLICT, "Пробный период уже использован"),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Подписка не найдена"),
    DEVICE_NOT_FOUND(HttpStatus.NOT_FOUND, "Устройство не найдено"),
    RETURN_BONUS_UNAVAILABLE(HttpStatus.CONFLICT, "Возвратный бонус недоступен"),
    RETURN_BONUS_ALREADY_USED(HttpStatus.CONFLICT, "Возвратный бонус уже использован"),
    PAYMENT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "Платежи временно недоступны"),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Платёж не найден"),
    PAYMENT_CREATION_UNCERTAIN(HttpStatus.CONFLICT, "Платёж проверяется. Не создавайте новый — обратитесь в поддержку"),
    INVALID_PAYMENT_CALLBACK(HttpStatus.BAD_REQUEST, "Некорректный callback платежа"),
    EXTERNAL_SERVICE_ERROR(HttpStatus.BAD_GATEWAY, "Внешний сервис временно недоступен"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Некорректный запрос"),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "Доступ запрещён");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
