package com.hotelatgangnam.smsbridge.core;

/** Conservative normalization for numbers accepted by Android's SmsManager. */
public final class PhoneNumberNormalizer {
    private PhoneNumberNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.regionMatches(true, 0, "tel:", 0, 4)) {
            value = value.substring(4);
        }

        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '0' && character <= '9') {
                result.append(character);
            } else if (character == '+' && result.length() == 0) {
                result.append(character);
            } else if (Character.isWhitespace(character)
                    || character == '-'
                    || character == '('
                    || character == ')'
                    || character == '.') {
                // Formatting characters are intentionally ignored.
            } else {
                return null;
            }
        }

        int digitCount = result.length() > 0 && result.charAt(0) == '+'
                ? result.length() - 1
                : result.length();
        if (digitCount < 8 || digitCount > 15) {
            return null;
        }
        return result.toString();
    }

    /**
     * Auto-replies are limited to international numbers and Korean mobile prefixes.
     * Staff can still send explicit Slack commands to any valid number.
     */
    public static boolean isLikelyPersonalMobile(String normalized) {
        if (normalized == null) {
            return false;
        }
        if (normalized.startsWith("+")) {
            return true;
        }
        return normalized.matches("01[016789][0-9]{7,8}");
    }
}
