package com.hotelatgangnam.smsbridge.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class MessageFingerprint {
    private MessageFingerprint() {}

    public static String inboundSms(
            String sender, String body, long providerTimestampMillis, int subscriptionId) {
        String normalized = PhoneNumberNormalizer.normalize(sender);
        String canonicalSender = normalized == null ? safe(sender).trim() : normalized;
        return sha256(
                canonicalSender
                        + "\u001f"
                        + safe(body)
                        + "\u001f"
                        + providerTimestampMillis
                        + "\u001f"
                        + subscriptionId);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(safe(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
