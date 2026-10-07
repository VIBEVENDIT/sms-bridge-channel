package com.hotelatgangnam.smsbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PhoneNumberNormalizerTest {
    @Test
    public void normalizesKoreanAndInternationalFormatting() {
        assertEquals("01012345678", PhoneNumberNormalizer.normalize("010-1234-5678"));
        assertEquals("+821012345678", PhoneNumberNormalizer.normalize("tel:+82 (10) 1234-5678"));
    }

    @Test
    public void rejectsShortCodesAndUnsafeCharacters() {
        assertNull(PhoneNumberNormalizer.normalize("12345"));
        assertNull(PhoneNumberNormalizer.normalize("010-1234-ABCD"));
        assertNull(PhoneNumberNormalizer.normalize("*23#01012345678"));
    }

    @Test
    public void autoReplyOnlyTargetsLikelyPersonalMobiles() {
        assertTrue(PhoneNumberNormalizer.isLikelyPersonalMobile("01012345678"));
        assertTrue(PhoneNumberNormalizer.isLikelyPersonalMobile("+14155552671"));
        assertFalse(PhoneNumberNormalizer.isLikelyPersonalMobile("15881234"));
    }
}
