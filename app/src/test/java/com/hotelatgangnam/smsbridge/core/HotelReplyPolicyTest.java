package com.hotelatgangnam.smsbridge.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HotelReplyPolicyTest {
    private final HotelReplyPolicy policy = new HotelReplyPolicy();

    @Test
    public void skipsOtpAdvertisementsAndAutomatedSenders() {
        assertSkipped("인증번호는 381992 입니다.", "otp");
        assertSkipped("(광고) 오늘 특가! 무료수신거부 080-123-4567", "advertisement");
        assertSkipped("자동 발송된 문자입니다. 회신 불가", "automated_or_noreply");
        assertSkipped("DO NOT REPLY. Reservation notice", "automated_or_noreply");
    }

    @Test
    public void skipsAcknowledgementsThatNeedNoReply() {
        assertSkipped("감사합니다!", "acknowledgement");
        assertSkipped("OK", "acknowledgement");
        assertSkipped("알겠습니다~", "acknowledgement");
    }

    @Test
    public void routesMoneyReservationAndSafetyQuestionsToPeople() {
        assertSkipped("예약 취소하고 환불 가능한가요?", "human_review_sensitive");
        assertSkipped("객실 변경하고 싶어요", "human_review_sensitive");
        assertSkipped("분실물이 있는데 확인 가능할까요?", "human_review_sensitive");
    }

    @Test
    public void answersOnlyEvidenceBackedFaqs() {
        HotelReplyPolicy.Plan luggage =
                policy.classify("체크인 전에 짐 보관 가능한가요?", "확인 후 답변드리겠습니다.");
        assertTrue(luggage.shouldReply);
        assertEquals("faq_luggage", luggage.reason);
        assertTrue(luggage.replyBody.contains("1층 셀프 보관"));

        HotelReplyPolicy.Plan late =
                policy.classify("밤 12시에 늦게 도착해도 되나요?", "확인 후 답변드리겠습니다.");
        assertTrue(late.shouldReply);
        assertEquals("faq_late_arrival", late.reason);
        assertTrue(late.replyBody.contains("1층 키오스크"));

        HotelReplyPolicy.Plan lounge =
                policy.classify("음식 먹을 공간은 몇 시까지인가요?", "확인 후 답변드리겠습니다.");
        assertTrue(lounge.shouldReply);
        assertEquals("faq_lounge", lounge.reason);
        assertTrue(lounge.replyBody.contains("21시"));
    }

    @Test
    public void acknowledgesGeneralQuestionsButNotStatements() {
        HotelReplyPolicy.Plan question =
                policy.classify("주차 가능한가요?", "확인 후 답변드리겠습니다.");
        assertTrue(question.shouldReply);
        assertEquals("general_question", question.reason);

        HotelReplyPolicy.Plan statement =
                policy.classify("오늘 저녁에 방문 예정", "확인 후 답변드리겠습니다.");
        assertFalse(statement.shouldReply);
        assertEquals("no_reply_needed_or_ambiguous", statement.reason);
    }

    private void assertSkipped(String body, String reason) {
        HotelReplyPolicy.Plan result =
                policy.classify(body, "확인 후 답변드리겠습니다.");
        assertFalse(result.shouldReply);
        assertEquals(reason, result.reason);
    }
}
