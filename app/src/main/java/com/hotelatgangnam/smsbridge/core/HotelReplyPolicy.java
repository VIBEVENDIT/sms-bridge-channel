package com.hotelatgangnam.smsbridge.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed hotel reply routing.
 *
 * <p>Only deterministic FAQs and messages that clearly ask a question receive an automatic SMS.
 * Reservation changes, money, safety, complaints, and ambiguous statements remain in Slack for a
 * person. Strong OTP, advertisement, and automated-sender markers are always skipped.
 */
public final class HotelReplyPolicy {
    private static final Pattern OTP = Pattern.compile(
            "(?is).*(OTP|ONE[ -]?TIME|VERIFICATION\\s*CODE|AUTH(?:ENTICATION)?\\s*CODE"
                    + "|인증\\s*(번호|코드)|보안\\s*코드).{0,30}\\d{4,8}.*");
    private static final Pattern ADVERTISEMENT = Pattern.compile(
            "(?is).*(\\(광고\\)|\\[광고\\]|무료\\s*수신\\s*거부|080[- )]"
                    + "|ADVERTISEMENT|UNSUBSCRIBE\\s*[:：]).*");
    private static final Pattern AUTOMATED = Pattern.compile(
            "(?is).*(NO[ -]?REPLY|NOREPLY|DO\\s*NOT\\s*REPLY|발신\\s*전용"
                    + "|회신\\s*(불가|금지)|자동\\s*(발송|전송)(된)?\\s*(문자|메시지)).*");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?is).*(취소|환불|결제|청구|카드|보상|무료\\s*취소|예약\\s*(변경|취소)"
                    + "|객실\\s*(변경|배정)|침대\\s*변경|성별|컴플레인|불만|사고|경찰|응급"
                    + "|다쳤|분실|도난|REFUND|CANCEL|PAYMENT|CHARGE|COMPLAINT|POLICE"
                    + "|EMERGENCY|LOST|STOLEN|ROOM\\s*CHANGE).*");
    private static final Pattern LUGGAGE = Pattern.compile(
            "(?is).*(짐|수하물|캐리어|보관|LUGGAGE|BAGGAGE|STORAGE).*");
    private static final Pattern LATE_ARRIVAL = Pattern.compile(
            "(?is).*(늦은\\s*체크인|늦게\\s*(도착|체크인)|심야\\s*(도착|체크인)"
                    + "|LATE\\s*(ARRIVAL|CHECK[ -]?IN)|ARRIV(E|AL).*(MIDNIGHT|LATE)).*");
    private static final Pattern LOUNGE = Pattern.compile(
            "(?is).*(라운지|카페|음식.*(공간|먹)|LOUNGE|CAFE|FOOD.*(AREA|SPACE)).*");
    private static final Pattern QUESTION = Pattern.compile(
            "(?is).*(\\?|문의|궁금|가능(한가요|할까요|한지|해요|한가|할 수)|되나요|있나요"
                    + "|언제|어디|어떻게|몇\\s*시|CAN\\s+I|COULD\\s+I|IS\\s+THERE"
                    + "|DO\\s+YOU|HOW|WHEN|WHERE|ですか|ますか|できますか).*");

    private static final Set<String> ACKNOWLEDGEMENTS =
            new HashSet<>(
                    Arrays.asList(
                            "네",
                            "넵",
                            "예",
                            "확인",
                            "확인했습니다",
                            "알겠습니다",
                            "감사합니다",
                            "고맙습니다",
                            "OK",
                            "OKAY",
                            "THANKYOU",
                            "THANKS",
                            "THX",
                            "はい",
                            "ありがとう",
                            "承知しました"));

    public Plan classify(String body, String genericAcknowledgement) {
        String value = body == null ? "" : body.trim();
        if (value.isEmpty()) {
            return Plan.skip("empty_message");
        }
        if (OTP.matcher(value).matches()) {
            return Plan.skip("otp");
        }
        if (ADVERTISEMENT.matcher(value).matches()) {
            return Plan.skip("advertisement");
        }
        if (AUTOMATED.matcher(value).matches()) {
            return Plan.skip("automated_or_noreply");
        }
        if (isAcknowledgement(value)) {
            return Plan.skip("acknowledgement");
        }
        if (SENSITIVE.matcher(value).matches()) {
            return Plan.skip("human_review_sensitive");
        }

        if (LUGGAGE.matcher(value).matches() && QUESTION.matcher(value).matches()) {
            return Plan.reply(
                    "faq_luggage",
                    "안녕하세요, 호텔 앳 강남입니다. 체크인 전·체크아웃 후 짐은 "
                            + "1층 셀프 보관 공간을 이용하실 수 있습니다. 귀중품은 직접 보관해 주세요.");
        }
        if (LATE_ARRIVAL.matcher(value).matches()) {
            return Plan.reply(
                    "faq_late_arrival",
                    "안녕하세요, 호텔 앳 강남입니다. 늦은 시간에도 1층 키오스크에서 "
                            + "셀프 체크인이 가능합니다. 예약자명과 예약번호를 준비해 주세요.");
        }
        if (LOUNGE.matcher(value).matches() && QUESTION.matcher(value).matches()) {
            return Plan.reply(
                    "faq_lounge",
                    "안녕하세요, 호텔 앳 강남입니다. 1층 라운지 카페는 21시까지 "
                            + "이용하실 수 있습니다.");
        }
        if (QUESTION.matcher(value).matches()
                && genericAcknowledgement != null
                && !genericAcknowledgement.trim().isEmpty()) {
            return Plan.reply("general_question", genericAcknowledgement.trim());
        }
        return Plan.skip("no_reply_needed_or_ambiguous");
    }

    private static boolean isAcknowledgement(String value) {
        String compact = value.toUpperCase(Locale.ROOT)
                .replaceAll("[\\s.!?,~♡♥👍🙏✅]+", "");
        return compact.length() <= 20 && ACKNOWLEDGEMENTS.contains(compact);
    }

    public static final class Plan {
        public final boolean shouldReply;
        public final String reason;
        public final String replyBody;

        private Plan(boolean shouldReply, String reason, String replyBody) {
            this.shouldReply = shouldReply;
            this.reason = reason;
            this.replyBody = replyBody;
        }

        public static Plan reply(String category, String body) {
            return new Plan(true, category, body);
        }

        public static Plan skip(String reason) {
            return new Plan(false, reason, null);
        }
    }
}
