package com.hotelatgangnam.smsbridge;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal Slack Web API adapter; tokens are supplied only from Android Keystore storage. */
final class SlackApiClient implements BridgeProcessor.SlackSink {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String API_ROOT = "https://slack.com/api/";
    private static final DateTimeFormatter KST =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'KST'")
                    .withZone(ZoneId.of("Asia/Seoul"));
    private static final Set<String> COMPLETED_WITHOUT_REPLY =
            new HashSet<>(
                    Arrays.asList(
                            "otp",
                            "advertisement",
                            "automated_or_noreply",
                            "acknowledgement",
                            "opt_out",
                            "empty_message",
                            "template_echo",
                            "recent_outbound_echo"));

    private final OkHttpClient http;
    private final String botToken;
    private final String channelId;

    SlackApiClient(OkHttpClient http, String botToken, String channelId) {
        this.http = http;
        this.botToken = botToken;
        this.channelId = channelId;
    }

    @Override
    public String postInbound(
            String phoneNumber,
            String body,
            String fingerprint,
            boolean autoReplyPlanned,
            String triageReason)
            throws Exception {
        boolean complete = !autoReplyPlanned && COMPLETED_WITHOUT_REPLY.contains(triageReason);
        String text =
                "*[문자수신]*"
                        + (complete ? " :완료:" : "")
                        + "\n*발신번호* = `"
                        + escape(phoneNumber)
                        + "`"
                        + "\n*수신일시* = "
                        + KST.format(Instant.now())
                        + "\n*문의내용* = "
                        + escape(body)
                        + "\n*분류* = "
                        + triageLabel(autoReplyPlanned, triageReason);

        JSONObject metadata = new JSONObject()
                .put("event_type", "hotel_sms_inbound")
                .put(
                        "event_payload",
                        new JSONObject()
                                .put("fingerprint", fingerprint)
                                .put("phone_number", phoneNumber)
                                .put("triage_reason", triageReason));
        JSONObject request = new JSONObject()
                .put("channel", channelId)
                .put("text", text)
                .put("unfurl_links", false)
                .put("unfurl_media", false)
                .put("client_msg_id", fingerprintUuid(fingerprint))
                .put("metadata", metadata);
        return call("chat.postMessage", request).getString("ts");
    }

    @Override
    public void postThreadStatus(String threadTimestamp, String text) throws Exception {
        JSONObject request = new JSONObject()
                .put("channel", channelId)
                .put("thread_ts", threadTimestamp)
                .put("text", text)
                .put("unfurl_links", false)
                .put("unfurl_media", false);
        call("chat.postMessage", request);
    }

    private JSONObject call(String method, JSONObject payload) throws IOException, JSONException {
        Request request = new Request.Builder()
                .url(API_ROOT + method)
                .header("Authorization", "Bearer " + botToken)
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("Slack HTTP " + response.code());
            }
            JSONObject result = new JSONObject(response.body().string());
            if (!result.optBoolean("ok")) {
                throw new IOException("Slack API: " + result.optString("error", "unknown_error"));
            }
            return result;
        }
    }

    private static String triageLabel(boolean autoReplyPlanned, String reason) {
        if (autoReplyPlanned) {
            switch (reason) {
                case "faq_luggage":
                    return "자동회신 예정 · 짐보관 FAQ";
                case "faq_late_arrival":
                    return "자동회신 예정 · 늦은 도착 FAQ";
                case "faq_lounge":
                    return "자동회신 예정 · 라운지 FAQ";
                default:
                    return "자동회신 예정 · 일반 문의";
            }
        }
        switch (reason) {
            case "otp":
                return "회신 제외 · OTP/인증번호";
            case "advertisement":
                return "회신 제외 · 광고";
            case "automated_or_noreply":
                return "회신 제외 · 자동발신/noreply";
            case "acknowledgement":
                return "회신 제외 · 단순 확인/감사";
            case "opt_out":
                return "회신 제외 · 수신거부";
            case "human_review_sensitive":
                return "담당자 확인 필요 · 예약/금전/안전 민감 문의";
            case "disabled":
                return "담당자 확인 필요 · 자동회신 꺼짐";
            case "no_reply_needed_or_ambiguous":
                return "담당자 확인 필요 · 모호한 내용";
            default:
                return "회신 제외 · " + reason;
        }
    }

    private static String fingerprintUuid(String fingerprint) {
        String value = (fingerprint + "00000000000000000000000000000000")
                .substring(0, 32);
        return value.substring(0, 8)
                + "-"
                + value.substring(8, 12)
                + "-"
                + value.substring(12, 16)
                + "-"
                + value.substring(16, 20)
                + "-"
                + value.substring(20, 32);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
