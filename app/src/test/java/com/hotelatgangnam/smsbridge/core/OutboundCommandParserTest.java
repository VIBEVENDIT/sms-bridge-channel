package com.hotelatgangnam.smsbridge.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutboundCommandParserTest {
    private final OutboundCommandParser parser = new OutboundCommandParser();

    @Test
    public void parsesConfiguredCommandAndMultilineBody() {
        OutboundCommandParser.Result result =
                parser.parse("!sms 010-1234-5678 안녕하세요\n호텔입니다.", "!sms");

        assertEquals(OutboundCommandParser.Result.Kind.VALID, result.kind);
        assertEquals("01012345678", result.phoneNumber);
        assertEquals("안녕하세요\n호텔입니다.", result.body);
    }

    @Test
    public void leavesNormalLoungeMessagesUntouched() {
        assertEquals(
                OutboundCommandParser.Result.Kind.NOT_COMMAND,
                parser.parse("SMS 점검 완료했습니다", "!sms").kind);
        assertEquals(
                OutboundCommandParser.Result.Kind.NOT_COMMAND,
                parser.parse("!sms잘못된접두어 01012345678 내용", "!sms").kind);
    }

    @Test
    public void explainsInvalidCommandsWithoutSending() {
        assertEquals(
                OutboundCommandParser.Result.Kind.INVALID,
                parser.parse("!sms 01012345678", "!sms").kind);
        assertEquals(
                OutboundCommandParser.Result.Kind.INVALID,
                parser.parse("!sms bad-number hello", "!sms").kind);
    }
}
