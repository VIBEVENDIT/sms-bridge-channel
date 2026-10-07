package com.hotelatgangnam.smsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;
import com.hotelatgangnam.smsbridge.core.MessageFingerprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable idempotency, thread routing, cooldown, and hourly-rate state. */
final class AndroidEventLedger implements BridgeProcessor.Ledger {
    private static final long HOUR_MILLIS = 60L * 60 * 1_000;
    private static final long THREAD_TTL_MILLIS = 30L * 24 * HOUR_MILLIS;

    private final SharedPreferences preferences;

    AndroidEventLedger(Context context) {
        preferences = context.getSharedPreferences("bridge_event_ledger", Context.MODE_PRIVATE);
    }

    @Override
    public synchronized boolean claim(
            String eventKey, long nowMillis, long ttlMillis) {
        String key = "event." + hash(eventKey);
        if (preferences.contains(key)) {
            long claimedAt = preferences.getLong(key, nowMillis);
            if (claimedAt >= nowMillis - ttlMillis) {
                return false;
            }
        }

        SharedPreferences.Editor editor = preferences.edit().putLong(key, nowMillis);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith("event.")
                    && entry.getValue() instanceof Long
                    && (Long) entry.getValue() < nowMillis - ttlMillis) {
                editor.remove(entry.getKey());
            }
        }
        return editor.commit();
    }

    @Override
    public synchronized void release(String eventKey) {
        preferences.edit().remove("event." + hash(eventKey)).commit();
    }

    @Override
    public synchronized boolean isRecentOutboundEcho(
            String phoneNumber, String body, long nowMillis, long windowMillis) {
        long sentAt = preferences.getLong(outboundKey(phoneNumber, body), Long.MIN_VALUE);
        long age = nowMillis - sentAt;
        return age >= 0 && age <= windowMillis;
    }

    @Override
    public synchronized BridgeProcessor.Reservation reserveAutoReply(
            String phoneNumber,
            long nowMillis,
            long senderCooldownMillis,
            int maxAutoRepliesPerHour,
            int maxTotalSmsPerHour) {
        String senderKey = "auto_sender." + hash(phoneNumber);
        long previous = preferences.getLong(senderKey, Long.MIN_VALUE);
        long elapsed = nowMillis - previous;
        if (elapsed >= 0 && elapsed < senderCooldownMillis) {
            return BridgeProcessor.Reservation.reject("발신자별 대기 시간");
        }

        List<Long> autoReplyTimes = recentTimes("auto_hour", nowMillis);
        if (autoReplyTimes.size() >= maxAutoRepliesPerHour) {
            return BridgeProcessor.Reservation.reject("자동 회신 시간당 한도");
        }
        List<Long> totalTimes = recentTimes("total_hour", nowMillis);
        if (totalTimes.size() >= maxTotalSmsPerHour) {
            return BridgeProcessor.Reservation.reject("전체 SMS 시간당 한도");
        }

        autoReplyTimes.add(nowMillis);
        totalTimes.add(nowMillis);
        boolean committed =
                preferences
                        .edit()
                        .putLong(senderKey, nowMillis)
                        .putString("auto_hour", encodeTimes(autoReplyTimes))
                        .putString("total_hour", encodeTimes(totalTimes))
                        .commit();
        return committed
                ? BridgeProcessor.Reservation.allow()
                : BridgeProcessor.Reservation.reject("상태 저장 실패");
    }

    @Override
    public synchronized BridgeProcessor.Reservation reserveSlackSend(
            long nowMillis, int maxTotalSmsPerHour) {
        List<Long> totalTimes = recentTimes("total_hour", nowMillis);
        if (totalTimes.size() >= maxTotalSmsPerHour) {
            return BridgeProcessor.Reservation.reject("전체 SMS 시간당 한도");
        }
        totalTimes.add(nowMillis);
        return preferences.edit().putString("total_hour", encodeTimes(totalTimes)).commit()
                ? BridgeProcessor.Reservation.allow()
                : BridgeProcessor.Reservation.reject("상태 저장 실패");
    }

    @Override
    public synchronized void recordOutbound(
            String phoneNumber, String body, long nowMillis) {
        preferences.edit().putLong(outboundKey(phoneNumber, body), nowMillis).apply();
    }

    @Override
    public synchronized void rememberThread(
            String threadTimestamp, String phoneNumber, long nowMillis) {
        preferences
                .edit()
                .putString(
                        threadKey(threadTimestamp),
                        nowMillis + "\n" + phoneNumber)
                .apply();
    }

    @Override
    public synchronized String findThreadRecipient(
            String threadTimestamp, long nowMillis) {
        String key = threadKey(threadTimestamp);
        String value = preferences.getString(key, null);
        if (value == null) {
            return null;
        }
        int separator = value.indexOf('\n');
        if (separator <= 0) {
            preferences.edit().remove(key).apply();
            return null;
        }
        try {
            long createdAt = Long.parseLong(value.substring(0, separator));
            if (nowMillis - createdAt > THREAD_TTL_MILLIS) {
                preferences.edit().remove(key).apply();
                return null;
            }
            return value.substring(separator + 1);
        } catch (NumberFormatException error) {
            preferences.edit().remove(key).apply();
            return null;
        }
    }

    private List<Long> recentTimes(String key, long nowMillis) {
        List<Long> result = new ArrayList<>();
        String encoded = preferences.getString(key, "");
        for (String item : encoded.split(",")) {
            try {
                long timestamp = Long.parseLong(item);
                long age = nowMillis - timestamp;
                if (age >= 0 && age < HOUR_MILLIS) {
                    result.add(timestamp);
                }
            } catch (NumberFormatException ignored) {
                // Corrupt entries are pruned on the next reservation.
            }
        }
        return result;
    }

    private static String encodeTimes(List<Long> times) {
        StringBuilder encoded = new StringBuilder();
        for (long timestamp : times) {
            if (encoded.length() > 0) {
                encoded.append(',');
            }
            encoded.append(timestamp);
        }
        return encoded.toString();
    }

    private static String outboundKey(String phoneNumber, String body) {
        return "outbound." + hash(phoneNumber + "\u001f" + body);
    }

    private static String threadKey(String threadTimestamp) {
        return "thread." + hash(threadTimestamp);
    }

    private static String hash(String value) {
        return MessageFingerprint.sha256(value);
    }
}
