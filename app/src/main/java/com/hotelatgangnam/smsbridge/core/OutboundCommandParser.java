package com.hotelatgangnam.smsbridge.core;

public final class OutboundCommandParser {
    public Result parse(String text, String configuredPrefix) {
        String prefix = configuredPrefix == null || configuredPrefix.trim().isEmpty()
                ? "!sms"
                : configuredPrefix.trim();
        String value = text == null ? "" : text.trim();

        if (!startsWithCommand(value, prefix)) {
            return Result.notCommand();
        }

        String remainder = value.substring(prefix.length()).trim();
        int separator = firstWhitespace(remainder);
        if (separator < 0) {
            return Result.invalid("사용법: " + prefix + " <전화번호> <내용>");
        }

        String rawNumber = remainder.substring(0, separator).trim();
        String body = remainder.substring(separator).trim();
        String number = PhoneNumberNormalizer.normalize(rawNumber);
        if (number == null) {
            return Result.invalid("전화번호 형식이 올바르지 않습니다.");
        }
        if (body.isEmpty()) {
            return Result.invalid("문자 내용이 비어 있습니다.");
        }
        if (body.length() > BridgeSettings.MAX_SMS_BODY_LENGTH) {
            return Result.invalid(
                    "문자 내용은 " + BridgeSettings.MAX_SMS_BODY_LENGTH + "자 이하여야 합니다.");
        }
        return Result.valid(number, body);
    }

    private static boolean startsWithCommand(String value, String prefix) {
        if (value.length() < prefix.length()
                || !value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return false;
        }
        return value.length() == prefix.length()
                || Character.isWhitespace(value.charAt(prefix.length()));
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    public static final class Result {
        public enum Kind {
            NOT_COMMAND,
            VALID,
            INVALID
        }

        public final Kind kind;
        public final String phoneNumber;
        public final String body;
        public final String error;

        private Result(Kind kind, String phoneNumber, String body, String error) {
            this.kind = kind;
            this.phoneNumber = phoneNumber;
            this.body = body;
            this.error = error;
        }

        public static Result notCommand() {
            return new Result(Kind.NOT_COMMAND, null, null, null);
        }

        public static Result valid(String phoneNumber, String body) {
            return new Result(Kind.VALID, phoneNumber, body, null);
        }

        public static Result invalid(String error) {
            return new Result(Kind.INVALID, null, null, error);
        }
    }
}
