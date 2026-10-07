package com.hotelatgangnam.smsbridge;

import android.content.Context;
import android.content.SharedPreferences;

import com.hotelatgangnam.smsbridge.core.BridgeSettings;

final class ConfigStore {
    static final String REQUIRED_CHANNEL_ID = "C0AN0CDAADC";
    static final String REQUIRED_CHANNEL_NAME = "#lounge-호텔앳강남";
    static final String DEFAULT_AUTO_REPLY =
            "안녕하세요, 호텔 앳 강남입니다. 문자 주셔서 감사합니다. 확인 후 답변드리겠습니다. "
                    + "긴급한 문의는 호텔로 전화 부탁드립니다.";

    private static final String BOT_TOKEN = "slack_bot_token";
    private static final String APP_TOKEN = "slack_app_token";

    private final SharedPreferences preferences;
    private final SecretStore secrets;

    ConfigStore(Context context) {
        preferences = context.getSharedPreferences("bridge_config", Context.MODE_PRIVATE);
        secrets = new SecretStore(context);
    }

    Snapshot read() {
        String botToken = secrets.get(BOT_TOKEN);
        String appToken = secrets.get(APP_TOKEN);
        return new Snapshot(
                botToken,
                appToken,
                REQUIRED_CHANNEL_ID,
                preferences.getString("command_prefix", "!sms"),
                preferences.getBoolean("auto_reply_enabled", false),
                preferences.getString("auto_reply_text", DEFAULT_AUTO_REPLY),
                preferences.getInt("auto_reply_cooldown_minutes", 360),
                preferences.getInt("max_auto_replies_per_hour", 20),
                preferences.getInt("max_total_sms_per_hour", 40),
                preferences.getString("blocked_numbers", ""),
                preferences.getString("allowed_slack_users", ""),
                preferences.getBoolean("bridge_enabled", false));
    }

    void save(Snapshot value) {
        secrets.put(BOT_TOKEN, value.botToken.trim());
        secrets.put(APP_TOKEN, value.appToken.trim());
        preferences
                .edit()
                .putString("channel_id", REQUIRED_CHANNEL_ID)
                .putString(
                        "command_prefix",
                        value.commandPrefix.trim().isEmpty()
                                ? "!sms"
                                : value.commandPrefix.trim())
                .putBoolean("auto_reply_enabled", value.autoReplyEnabled)
                .putString("auto_reply_text", value.autoReplyText.trim())
                .putInt("auto_reply_cooldown_minutes", clamp(value.cooldownMinutes, 1, 43_200))
                .putInt("max_auto_replies_per_hour", clamp(value.maxAutoRepliesPerHour, 1, 500))
                .putInt("max_total_sms_per_hour", clamp(value.maxTotalSmsPerHour, 1, 1_000))
                .putString("blocked_numbers", value.blockedNumbers.trim())
                .putString("allowed_slack_users", value.allowedSlackUsers.trim())
                .putBoolean("bridge_enabled", value.enabled)
                .apply();
    }

    void setEnabled(boolean enabled) {
        preferences.edit().putBoolean("bridge_enabled", enabled).commit();
    }

    boolean isEnabled() {
        return preferences.getBoolean("bridge_enabled", false);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class Snapshot {
        final String botToken;
        final String appToken;
        final String channelId;
        final String commandPrefix;
        final boolean autoReplyEnabled;
        final String autoReplyText;
        final int cooldownMinutes;
        final int maxAutoRepliesPerHour;
        final int maxTotalSmsPerHour;
        final String blockedNumbers;
        final String allowedSlackUsers;
        final boolean enabled;

        Snapshot(
                String botToken,
                String appToken,
                String channelId,
                String commandPrefix,
                boolean autoReplyEnabled,
                String autoReplyText,
                int cooldownMinutes,
                int maxAutoRepliesPerHour,
                int maxTotalSmsPerHour,
                String blockedNumbers,
                String allowedSlackUsers,
                boolean enabled) {
            this.botToken = botToken == null ? "" : botToken;
            this.appToken = appToken == null ? "" : appToken;
            this.channelId = channelId == null ? "" : channelId;
            this.commandPrefix = commandPrefix == null ? "!sms" : commandPrefix;
            this.autoReplyEnabled = autoReplyEnabled;
            this.autoReplyText = autoReplyText == null ? "" : autoReplyText;
            this.cooldownMinutes = cooldownMinutes;
            this.maxAutoRepliesPerHour = maxAutoRepliesPerHour;
            this.maxTotalSmsPerHour = maxTotalSmsPerHour;
            this.blockedNumbers = blockedNumbers == null ? "" : blockedNumbers;
            this.allowedSlackUsers = allowedSlackUsers == null ? "" : allowedSlackUsers;
            this.enabled = enabled;
        }

        boolean isReady() {
            return botToken.startsWith("xoxb-")
                    && appToken.startsWith("xapp-")
                    && REQUIRED_CHANNEL_ID.equals(channelId);
        }

        BridgeSettings toBridgeSettings() {
            return new BridgeSettings(
                    channelId,
                    commandPrefix,
                    autoReplyEnabled,
                    autoReplyText,
                    cooldownMinutes * 60_000L,
                    maxAutoRepliesPerHour,
                    maxTotalSmsPerHour,
                    BridgeSettings.parseList(blockedNumbers),
                    BridgeSettings.parseList(allowedSlackUsers));
        }
    }
}
