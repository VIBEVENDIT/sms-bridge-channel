package com.hotelatgangnam.smsbridge.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure policy checks. Durable cooldown/rate reservations are handled by the ledger. */
public final class AutoReplyPolicy {
    private static final Set<String> OPT_OUT_WORDS =
            new HashSet<>(
                    Arrays.asList(
                            "STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT"));
    private final HotelReplyPolicy hotelReplyPolicy = new HotelReplyPolicy();

    public Decision evaluate(
            BridgeSettings settings,
            String rawSender,
            String inboundBody,
            boolean matchesRecentOutbound) {
        if (!settings.autoReplyEnabled) {
            return Decision.suppress("disabled");
        }

        String senderLabel =
                rawSender == null ? "" : rawSender.toUpperCase(Locale.ROOT).replace("-", "");
        if (senderLabel.contains("NOREPLY")
                || senderLabel.contains("DONOTREPLY")
                || senderLabel.contains("발신전용")) {
            return Decision.suppress("automated_or_noreply");
        }

        String sender = PhoneNumberNormalizer.normalize(rawSender);
        if (!PhoneNumberNormalizer.isLikelyPersonalMobile(sender)) {
            return Decision.suppress("non_mobile_sender");
        }
        if (settings.isNumberBlocked(sender)) {
            return Decision.suppress("blocked_sender");
        }

        String canonicalBody = canonical(inboundBody);
        if (canonicalBody.isEmpty()) {
            return Decision.suppress("empty_message");
        }
        if (isOptOut(canonicalBody)) {
            return Decision.suppress("opt_out");
        }

        HotelReplyPolicy.Plan plan =
                hotelReplyPolicy.classify(inboundBody, settings.renderAutoReply(sender));
        if (!plan.shouldReply) {
            return Decision.suppress(plan.reason);
        }

        String renderedReply = plan.replyBody;
        String canonicalReply = canonical(renderedReply);
        if (canonicalReply.isEmpty()) {
            return Decision.suppress("empty_template");
        }
        if (canonicalBody.equals(canonicalReply)
                || (canonicalReply.length() >= 20 && canonicalBody.contains(canonicalReply))) {
            return Decision.suppress("template_echo");
        }
        if (matchesRecentOutbound) {
            return Decision.suppress("recent_outbound_echo");
        }
        return Decision.allow(sender, renderedReply, plan.reason);
    }

    private static boolean isOptOut(String canonicalBody) {
        String compact = canonicalBody.replaceAll("[^A-Z가-힣]", "");
        return OPT_OUT_WORDS.contains(compact)
                || compact.contains("수신거부")
                || compact.equals("거부");
    }

    private static String canonical(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    public static final class Decision {
        public final boolean allowed;
        public final String reason;
        public final String phoneNumber;
        public final String replyBody;

        private Decision(
                boolean allowed, String reason, String phoneNumber, String replyBody) {
            this.allowed = allowed;
            this.reason = reason;
            this.phoneNumber = phoneNumber;
            this.replyBody = replyBody;
        }

        public static Decision allow(String phoneNumber, String body, String category) {
            return new Decision(true, category, phoneNumber, body);
        }

        public static Decision suppress(String reason) {
            return new Decision(false, reason, null, null);
        }
    }
}
