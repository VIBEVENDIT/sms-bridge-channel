package com.hotelatgangnam.smsbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class BridgeProcessorIntegrationTest {
    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void receiveSlackAutoReplyAndHumanOutboundAreIdempotent() {
        FakeLedger ledger = new FakeLedger();
        FakeSms sms = new FakeSms();
        FakeSlack slack = new FakeSlack();
        BridgeProcessor processor = new BridgeProcessor(ledger, sms, slack);

        BridgeProcessor.InboundSms inbound =
                new BridgeProcessor.InboundSms(
                        "010-1234-5678", "체크인 전에 짐 보관 가능한가요?", NOW - 1_000, 1);
        BridgeProcessor.Outcome inboundResult =
                processor.handleInbound(inbound, settings(), NOW);

        assertEquals(BridgeProcessor.Outcome.Kind.SENT, inboundResult.kind);
        assertEquals(1, slack.inbound.size());
        assertEquals(1, sms.sent.size());
        assertTrue(sms.sent.get(0).contains("1층 셀프 보관"));
        assertTrue(slack.statuses.get(0).contains(":완료:"));

        BridgeProcessor.Outcome duplicateInbound =
                processor.handleInbound(inbound, settings(), NOW + 1);
        assertEquals(BridgeProcessor.Outcome.Kind.IGNORED, duplicateInbound.kind);
        assertEquals(1, slack.inbound.size());
        assertEquals(1, sms.sent.size());

        BridgeProcessor.SlackMessage threadReply =
                new BridgeProcessor.SlackMessage(
                        "Ev-thread-1",
                        "C0AN0CDAADC",
                        "U-HOTEL",
                        "추가로 프론트에 말씀해 주세요.",
                        "1800000001.000100",
                        FakeSlack.THREAD_TS);
        BridgeProcessor.Outcome threadResult =
                processor.handleSlack(threadReply, settings(), NOW + 2_000);
        assertEquals(BridgeProcessor.Outcome.Kind.SENT, threadResult.kind);
        assertEquals(2, sms.sent.size());
        assertTrue(sms.sent.get(1).startsWith("01012345678|"));

        BridgeProcessor.Outcome duplicateSlack =
                processor.handleSlack(threadReply, settings(), NOW + 3_000);
        assertEquals(BridgeProcessor.Outcome.Kind.IGNORED, duplicateSlack.kind);
        assertEquals(2, sms.sent.size());

        BridgeProcessor.SlackMessage command =
                new BridgeProcessor.SlackMessage(
                        "Ev-command-1",
                        "C0AN0CDAADC",
                        "U-HOTEL",
                        "!sms +82-10-9876-5432 새 예약 문의 답변입니다.",
                        "1800000002.000100",
                        null);
        assertEquals(
                BridgeProcessor.Outcome.Kind.SENT,
                processor.handleSlack(command, settings(), NOW + 4_000).kind);
        assertEquals(3, sms.sent.size());
        assertTrue(sms.sent.get(2).startsWith("+821098765432|"));
    }

    @Test
    public void otpIsVisibleInSlackButNeverAutoReplied() {
        FakeSms sms = new FakeSms();
        FakeSlack slack = new FakeSlack();
        BridgeProcessor processor = new BridgeProcessor(new FakeLedger(), sms, slack);

        BridgeProcessor.Outcome outcome =
                processor.handleInbound(
                        new BridgeProcessor.InboundSms(
                                "01011112222", "인증번호 849201 입니다.", NOW, 1),
                        settings(),
                        NOW);

        assertEquals(BridgeProcessor.Outcome.Kind.PROCESSED, outcome.kind);
        assertEquals(1, slack.inbound.size());
        assertTrue(slack.inbound.get(0).contains("otp"));
        assertTrue(sms.sent.isEmpty());
    }

    @Test
    public void slackPostFailureIsRetriedBeforeAnyAutoReply() {
        FakeLedger ledger = new FakeLedger();
        FakeSms sms = new FakeSms();
        FakeSlack slack = new FakeSlack();
        slack.failNextInbound = true;
        BridgeProcessor processor = new BridgeProcessor(ledger, sms, slack);
        BridgeProcessor.InboundSms inbound =
                new BridgeProcessor.InboundSms(
                        "01012345678", "짐 보관 가능한가요?", NOW, 1);

        BridgeProcessor.Outcome first =
                processor.handleInbound(inbound, settings(), NOW);
        assertEquals(BridgeProcessor.Outcome.Kind.RETRY, first.kind);
        assertTrue(sms.sent.isEmpty());

        BridgeProcessor.Outcome retry =
                processor.handleInbound(inbound, settings(), NOW + 1_000);
        assertEquals(BridgeProcessor.Outcome.Kind.SENT, retry.kind);
        assertEquals(1, sms.sent.size());
    }

    @Test
    public void unauthorizedSlackUserCannotSendSms() {
        BridgeSettings restricted =
                new BridgeSettings(
                        "C0AN0CDAADC",
                        "!sms",
                        true,
                        "확인 후 답변드리겠습니다.",
                        60_000,
                        20,
                        40,
                        Collections.emptySet(),
                        Collections.singleton("U-ALLOWED"));
        FakeSms sms = new FakeSms();
        BridgeProcessor processor =
                new BridgeProcessor(new FakeLedger(), sms, new FakeSlack());
        BridgeProcessor.Outcome outcome =
                processor.handleSlack(
                        new BridgeProcessor.SlackMessage(
                                "Ev-denied",
                                "C0AN0CDAADC",
                                "U-DENIED",
                                "!sms 01012345678 보내면 안 됨",
                                "1800000003.1",
                                null),
                        restricted,
                        NOW);
        assertEquals("slack_user_not_allowed", outcome.reason);
        assertTrue(sms.sent.isEmpty());
    }

    private static BridgeSettings settings() {
        return new BridgeSettings(
                "C0AN0CDAADC",
                "!sms",
                true,
                "안녕하세요, 호텔 앳 강남입니다. 문의를 확인한 뒤 답변드리겠습니다.",
                60_000,
                20,
                40,
                Collections.emptySet(),
                Collections.emptySet());
    }

    private static final class FakeLedger implements BridgeProcessor.Ledger {
        private final Set<String> claimed = new HashSet<>();
        private final Map<String, String> threads = new HashMap<>();
        private final Map<String, Long> outbound = new HashMap<>();

        @Override
        public boolean claim(String eventKey, long nowMillis, long ttlMillis) {
            return claimed.add(eventKey);
        }

        @Override
        public void release(String eventKey) {
            claimed.remove(eventKey);
        }

        @Override
        public boolean isRecentOutboundEcho(
                String phoneNumber, String body, long nowMillis, long windowMillis) {
            Long sentAt = outbound.get(phoneNumber + "|" + body);
            return sentAt != null && nowMillis - sentAt <= windowMillis;
        }

        @Override
        public BridgeProcessor.Reservation reserveAutoReply(
                String phoneNumber,
                long nowMillis,
                long senderCooldownMillis,
                int maxAutoRepliesPerHour,
                int maxTotalSmsPerHour) {
            return BridgeProcessor.Reservation.allow();
        }

        @Override
        public BridgeProcessor.Reservation reserveSlackSend(
                long nowMillis, int maxTotalSmsPerHour) {
            return BridgeProcessor.Reservation.allow();
        }

        @Override
        public void recordOutbound(String phoneNumber, String body, long nowMillis) {
            outbound.put(phoneNumber + "|" + body, nowMillis);
        }

        @Override
        public void rememberThread(
                String threadTimestamp, String phoneNumber, long nowMillis) {
            threads.put(threadTimestamp, phoneNumber);
        }

        @Override
        public String findThreadRecipient(String threadTimestamp, long nowMillis) {
            return threads.get(threadTimestamp);
        }
    }

    private static final class FakeSms implements BridgeProcessor.SmsSender {
        private final List<String> sent = new ArrayList<>();

        @Override
        public void send(String phoneNumber, String body) {
            sent.add(phoneNumber + "|" + body);
        }
    }

    private static final class FakeSlack implements BridgeProcessor.SlackSink {
        static final String THREAD_TS = "1800000000.000100";
        private final List<String> inbound = new ArrayList<>();
        private final List<String> statuses = new ArrayList<>();
        private boolean failNextInbound;

        @Override
        public String postInbound(
                String phoneNumber,
                String body,
                String fingerprint,
                boolean autoReplyPlanned,
                String triageReason)
                throws Exception {
            if (failNextInbound) {
                failNextInbound = false;
                throw new IllegalStateException("offline");
            }
            inbound.add(phoneNumber + "|" + body + "|" + triageReason);
            return THREAD_TS;
        }

        @Override
        public void postThreadStatus(String threadTimestamp, String text) {
            statuses.add(threadTimestamp + "|" + text);
        }
    }
}
