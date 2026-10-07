package com.hotelatgangnam.smsbridge.core;

/**
 * Side-effect coordinator shared by the Android service and local unit tests.
 *
 * <p>Every external event is durably claimed before an SMS side effect. This intentionally provides
 * at-most-once sending: after an uncertain modem failure, staff must issue a new Slack command rather
 * than risk a duplicate billable SMS.
 */
public final class BridgeProcessor {
    static final long EVENT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1_000;
    static final long OUTBOUND_ECHO_WINDOW_MILLIS = 5L * 60 * 1_000;

    private final Ledger ledger;
    private final SmsSender smsSender;
    private final SlackSink slack;
    private final AutoReplyPolicy autoReplyPolicy;
    private final OutboundCommandParser commandParser;

    public BridgeProcessor(Ledger ledger, SmsSender smsSender, SlackSink slack) {
        this.ledger = ledger;
        this.smsSender = smsSender;
        this.slack = slack;
        this.autoReplyPolicy = new AutoReplyPolicy();
        this.commandParser = new OutboundCommandParser();
    }

    public Outcome handleInbound(
            InboundSms message, BridgeSettings settings, long nowMillis) {
        String fingerprint = MessageFingerprint.inboundSms(
                message.sender,
                message.body,
                message.providerTimestampMillis,
                message.subscriptionId);
        String eventKey = "android-sms:" + fingerprint;
        if (!ledger.claim(eventKey, nowMillis, EVENT_TTL_MILLIS)) {
            return Outcome.ignored("duplicate_inbound");
        }

        String normalized = PhoneNumberNormalizer.normalize(message.sender);
        String sender = normalized == null ? message.sender : normalized;
        boolean recentEcho =
                normalized != null
                        && ledger.isRecentOutboundEcho(
                                normalized,
                                message.body,
                                nowMillis,
                                OUTBOUND_ECHO_WINDOW_MILLIS);
        AutoReplyPolicy.Decision decision =
                autoReplyPolicy.evaluate(settings, message.sender, message.body, recentEcho);

        String threadTimestamp = null;
        try {
            threadTimestamp =
                    slack.postInbound(
                            sender,
                            message.body,
                            fingerprint,
                            decision.allowed,
                            decision.reason);
            if (threadTimestamp != null
                    && !threadTimestamp.trim().isEmpty()
                    && normalized != null) {
                ledger.rememberThread(threadTimestamp, normalized, nowMillis);
            }
        } catch (Exception error) {
            ledger.release(eventKey);
            return Outcome.retry("slack_inbound_failed");
        }

        if (!decision.allowed) {
            return Outcome.processed("inbound_posted_auto_" + decision.reason);
        }

        Reservation reservation =
                ledger.reserveAutoReply(
                        decision.phoneNumber,
                        nowMillis,
                        settings.autoReplyCooldownMillis,
                        settings.maxAutoRepliesPerHour,
                        settings.maxTotalSmsPerHour);
        if (!reservation.allowed) {
            postStatusQuietly(
                    threadTimestamp,
                    "⏭️ 자동 회신 생략: " + reservation.reason);
            return Outcome.processed("auto_" + reservation.reason);
        }

        try {
            smsSender.send(decision.phoneNumber, decision.replyBody);
            ledger.recordOutbound(decision.phoneNumber, decision.replyBody, nowMillis);
            postStatusQuietly(
                    threadTimestamp,
                    ":완료: 자동회신 SMS 전송 대기 완료 · " + decision.reason);
            return Outcome.sent("auto_reply_sent");
        } catch (Exception error) {
            postStatusQuietly(
                    threadTimestamp, "⚠️ 자동 회신 실패: " + safeError(error));
            return Outcome.failed("auto_reply_failed");
        }
    }

    public Outcome handleSlack(
            SlackMessage message, BridgeSettings settings, long nowMillis) {
        if (!settings.channelId.equals(message.channelId)) {
            return Outcome.ignored("wrong_channel");
        }
        if (!settings.isSlackUserAllowed(message.userId)) {
            return Outcome.ignored("slack_user_not_allowed");
        }

        String targetNumber = null;
        String body = null;
        if (message.threadTimestamp != null
                && !message.threadTimestamp.trim().isEmpty()) {
            targetNumber = ledger.findThreadRecipient(message.threadTimestamp, nowMillis);
            if (targetNumber != null) {
                body = message.text == null ? "" : message.text.trim();
                if (body.isEmpty() || body.length() > BridgeSettings.MAX_SMS_BODY_LENGTH) {
                    claimAndReportInvalid(
                            message,
                            nowMillis,
                            body.isEmpty()
                                    ? "문자 내용이 비어 있습니다."
                                    : "문자 내용은 "
                                            + BridgeSettings.MAX_SMS_BODY_LENGTH
                                            + "자 이하여야 합니다.");
                    return Outcome.ignored("invalid_thread_reply");
                }
            }
        }

        if (targetNumber == null) {
            OutboundCommandParser.Result command =
                    commandParser.parse(message.text, settings.commandPrefix);
            if (command.kind == OutboundCommandParser.Result.Kind.NOT_COMMAND) {
                return Outcome.ignored("not_an_sms_command");
            }
            if (command.kind == OutboundCommandParser.Result.Kind.INVALID) {
                claimAndReportInvalid(message, nowMillis, command.error);
                return Outcome.ignored("invalid_sms_command");
            }
            targetNumber = command.phoneNumber;
            body = command.body;
        }

        if (settings.isNumberBlocked(targetNumber)) {
            claimAndReportInvalid(message, nowMillis, "차단된 전화번호입니다.");
            return Outcome.ignored("blocked_recipient");
        }

        String eventKey = slackEventKey(message);
        if (!ledger.claim(eventKey, nowMillis, EVENT_TTL_MILLIS)) {
            return Outcome.ignored("duplicate_slack_event");
        }

        Reservation reservation =
                ledger.reserveSlackSend(nowMillis, settings.maxTotalSmsPerHour);
        if (!reservation.allowed) {
            postStatusQuietly(replyThread(message), "⏭️ 문자 전송 생략: " + reservation.reason);
            return Outcome.ignored("outbound_" + reservation.reason);
        }

        try {
            smsSender.send(targetNumber, body);
            ledger.recordOutbound(targetNumber, body, nowMillis);
            postStatusQuietly(
                    replyThread(message), ":완료: SMS 전송 대기 완료 · " + targetNumber);
            return Outcome.sent("slack_sms_sent");
        } catch (Exception error) {
            postStatusQuietly(
                    replyThread(message), "⚠️ SMS 전송 실패: " + safeError(error));
            return Outcome.failed("slack_sms_failed");
        }
    }

    private void claimAndReportInvalid(
            SlackMessage message, long nowMillis, String error) {
        if (ledger.claim(slackEventKey(message), nowMillis, EVENT_TTL_MILLIS)) {
            postStatusQuietly(replyThread(message), "⚠️ " + error);
        }
    }

    private void postStatusQuietly(String threadTimestamp, String text) {
        if (threadTimestamp == null || threadTimestamp.trim().isEmpty()) {
            return;
        }
        try {
            slack.postThreadStatus(threadTimestamp, text);
        } catch (Exception ignored) {
            // SMS delivery must not be retried merely because the Slack audit failed.
        }
    }

    private static String replyThread(SlackMessage message) {
        return message.threadTimestamp == null || message.threadTimestamp.trim().isEmpty()
                ? message.timestamp
                : message.threadTimestamp;
    }

    private static String slackEventKey(SlackMessage message) {
        return "slack:" + message.channelId + ":" + message.timestamp;
    }

    private static String safeError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    public interface Ledger {
        boolean claim(String eventKey, long nowMillis, long ttlMillis);

        void release(String eventKey);

        boolean isRecentOutboundEcho(
                String phoneNumber, String body, long nowMillis, long windowMillis);

        Reservation reserveAutoReply(
                String phoneNumber,
                long nowMillis,
                long senderCooldownMillis,
                int maxAutoRepliesPerHour,
                int maxTotalSmsPerHour);

        Reservation reserveSlackSend(long nowMillis, int maxTotalSmsPerHour);

        void recordOutbound(String phoneNumber, String body, long nowMillis);

        void rememberThread(String threadTimestamp, String phoneNumber, long nowMillis);

        String findThreadRecipient(String threadTimestamp, long nowMillis);
    }

    public interface SmsSender {
        void send(String phoneNumber, String body) throws Exception;
    }

    public interface SlackSink {
        String postInbound(
                        String phoneNumber,
                        String body,
                        String fingerprint,
                        boolean autoReplyPlanned,
                        String triageReason)
                throws Exception;

        void postThreadStatus(String threadTimestamp, String text) throws Exception;
    }

    public static final class InboundSms {
        public final String sender;
        public final String body;
        public final long providerTimestampMillis;
        public final int subscriptionId;

        public InboundSms(
                String sender,
                String body,
                long providerTimestampMillis,
                int subscriptionId) {
            this.sender = sender == null ? "" : sender;
            this.body = body == null ? "" : body;
            this.providerTimestampMillis = providerTimestampMillis;
            this.subscriptionId = subscriptionId;
        }
    }

    public static final class SlackMessage {
        public final String eventId;
        public final String channelId;
        public final String userId;
        public final String text;
        public final String timestamp;
        public final String threadTimestamp;

        public SlackMessage(
                String eventId,
                String channelId,
                String userId,
                String text,
                String timestamp,
                String threadTimestamp) {
            this.eventId = eventId;
            this.channelId = channelId == null ? "" : channelId;
            this.userId = userId == null ? "" : userId;
            this.text = text == null ? "" : text;
            this.timestamp = timestamp == null ? "" : timestamp;
            this.threadTimestamp = threadTimestamp;
        }
    }

    public static final class Reservation {
        public final boolean allowed;
        public final String reason;

        private Reservation(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static Reservation allow() {
            return new Reservation(true, "allowed");
        }

        public static Reservation reject(String reason) {
            return new Reservation(false, reason);
        }
    }

    public static final class Outcome {
        public enum Kind {
            IGNORED,
            PROCESSED,
            SENT,
            FAILED,
            RETRY
        }

        public final Kind kind;
        public final String reason;

        private Outcome(Kind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        public static Outcome ignored(String reason) {
            return new Outcome(Kind.IGNORED, reason);
        }

        public static Outcome processed(String reason) {
            return new Outcome(Kind.PROCESSED, reason);
        }

        public static Outcome sent(String reason) {
            return new Outcome(Kind.SENT, reason);
        }

        public static Outcome failed(String reason) {
            return new Outcome(Kind.FAILED, reason);
        }

        public static Outcome retry(String reason) {
            return new Outcome(Kind.RETRY, reason);
        }
    }
}
