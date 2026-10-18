package com.abcvpn.dto;

public record RemnawaveWebhook(String scope,String event,Data data) {
    public record Data(User user,HwidUserDevice hwidUserDevice) {}
    public record User(Long telegramId) {}
    public record HwidUserDevice(String hwid,String platform,String osVersion,String deviceModel) {}
}
