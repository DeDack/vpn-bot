package com.abcvpn.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OneClickSignature {
    private OneClickSignature() {}

    public static String create(long telegramId,long expiresAt,String secret) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            String payload=telegramId+":"+expiresAt;
            return java.util.HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch(Exception exception) {
            throw new IllegalStateException("Cannot sign one-click link",exception);
        }
    }

    public static boolean valid(long telegramId,long expiresAt,String signature,String secret) {
        if(signature==null) return false;
        return MessageDigest.isEqual(create(telegramId,expiresAt,secret).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
