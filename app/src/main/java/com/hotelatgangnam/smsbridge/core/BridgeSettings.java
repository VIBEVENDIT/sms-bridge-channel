package com.hotelatgangnam.smsbridge.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Immutable, secret-free settings consumed by the bridge core. */
public final class BridgeSettings {
    public static final int MAX_SMS_BODY_LENGTH = 1_000;

    public final String channelId;
    public final String commandPrefix;
    public final boolean autoReplyEnabled;
    public final String autoReplyText;
    public final long autoReplyCooldownMillis;
    public final int maxAutoRepliesPerHour;
    public final int maxTotalSmsPerHour;
    public final Set<String> blockedNumbers;
    public final Set<String> allowedSlackUsers;

    public BridgeSettings(
            String channelId,
            String commandPrefix,
            boolean autoReplyEnabled,
            String autoReplyText,
            long autoReplyCooldownMillis,
            int maxAutoRepliesPerHour,
            int maxTotalSmsPerHour,
            Set<String> blockedNumbers,
            Set<String> allowedSlackUsers) {
        this.channelId = clean(channelId);
        this.commandPrefix = clean(commandPrefix).isEmpty() ? "!sms" : clean(commandPrefix);
        this.autoReplyEnabled = autoReplyEnabled;
        this.autoReplyText = autoReplyText == null ? "" : autoReplyText.trim();
        this.autoReplyCooldownMillis = Math.max(0L, autoReplyCooldownMillis);
        this.maxAutoRepliesPerHour = Math.max(1, maxAutoRepliesPerHour);
        this.maxTotalSmsPerHour = Math.max(this.maxAutoRepliesPerHour, maxTotalSmsPerHour);
        this.blockedNumbers = immutableNormalizedNumbers(blockedNumbers);
        this.allowedSlackUsers = immutableUppercase(allowedSlackUsers);
    }

    public boolean isSlackUserAllowed(String userId) {
        return allowedSlackUsers.isEmpty()
                || allowedSlackUsers.contains(clean(userId).toUpperCase(Locale.ROOT));
    }

    public boolean isNumberBlocked(String number) {
        String normalized = PhoneNumberNormalizer.normalize(number);
        return normalized != null && blockedNumbers.contains(normalized);
    }

    public String renderAutoReply(String phoneNumber) {
        return autoReplyText.replace("{phone}", phoneNumber == null ? "" : phoneNumber);
    }

    public static Set<String> parseList(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String item : value.split("[,\\n]")) {
            String cleaned = item.trim();
            if (!cleaned.isEmpty()) {
                values.add(cleaned);
            }
        }
        return values;
    }

    private static Set<String> immutableNormalizedNumbers(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String normalized = PhoneNumberNormalizer.normalize(value);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> immutableUppercase(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String cleaned = clean(value);
                if (!cleaned.isEmpty()) {
                    result.add(cleaned.toUpperCase(Locale.ROOT));
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
