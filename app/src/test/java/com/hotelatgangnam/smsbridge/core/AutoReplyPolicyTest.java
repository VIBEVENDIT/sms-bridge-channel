package com.hotelatgangnam.smsbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

public class AutoReplyPolicyTest {
    private final AutoReplyPolicy policy = new AutoReplyPolicy();

    @Test
    public void blocksTemplateEchoAndRecentOutboundEcho() {
        BridgeSettings settings = settings(true);
        AutoReplyPolicy.Decision templateEcho =
                policy.evaluate(
                        settings,
                        "010-1234-5678",
                        settings.autoReplyText,
                        false);
        assertFalse(templateEcho.allowed);
        assertEquals("no_reply_needed_or_ambiguous", templateEcho.reason);

        AutoReplyPolicy.Decision recentEcho =
                policy.evaluate(
                        settings,
                        "010-1234-5678",
                        "짐 보관 가능한가요?",
                        true);
        assertFalse(recentEcho.allowed);
        assertEquals("recent_outbound_echo", recentEcho.reason);
    }

    @Test
    public void refusesNonMobileAndBlockedSenders() {
        assertEquals(
                "non_mobile_sender",
                policy.evaluate(settings(true), "1588-1234", "문의 가능한가요?", false)
                        .reason);

        BridgeSettings blocked =
                new BridgeSettings(
                        "C0AN0CDAADC",
                        "!sms",
                        true,
                        "확인 후 답변드리겠습니다.",
                        60_000,
                        20,
                        40,
                        Collections.singleton("01012345678"),
                        Collections.emptySet());
        assertEquals(
                "blocked_sender",
                policy.evaluate(blocked, "010-1234-5678", "문의 가능한가요?", false)
                        .reason);
    }

    @Test
    public void allowsFaqWhenEnabled() {
        AutoReplyPolicy.Decision decision =
                policy.evaluate(
                        settings(true),
                        "010-1234-5678",
                        "짐 보관 가능한가요?",
                        false);
        assertTrue(decision.allowed);
        assertEquals("faq_luggage", decision.reason);
    }

    private static BridgeSettings settings(boolean enabled) {
        return new BridgeSettings(
                "C0AN0CDAADC",
                "!sms",
                enabled,
                "확인 후 답변드리겠습니다.",
                60_000,
                20,
                40,
                Collections.emptySet(),
                Collections.emptySet());
    }
}
