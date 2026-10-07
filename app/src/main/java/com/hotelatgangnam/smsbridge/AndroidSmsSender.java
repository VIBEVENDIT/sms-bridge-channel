package com.hotelatgangnam.smsbridge;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionManager;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Sends through the phone SIM and waits until Android reports every SMS part as sent. */
final class AndroidSmsSender implements BridgeProcessor.SmsSender {
    private static final long SEND_TIMEOUT_SECONDS = 45;

    private final Context context;

    AndroidSmsSender(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void send(String phoneNumber, String body) throws Exception {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("SMS 발신 권한이 없습니다.");
        }

        SmsManager manager = smsManager();
        ArrayList<String> parts = manager.divideMessage(body);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("문자 내용이 비어 있습니다.");
        }

        String action =
                context.getPackageName() + ".SMS_SENT." + UUID.randomUUID();
        CountDownLatch sentParts = new CountDownLatch(parts.size());
        AtomicReference<String> failure = new AtomicReference<>();
        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context ignored, Intent intent) {
                        if (getResultCode() != Activity.RESULT_OK) {
                            failure.compareAndSet(
                                    null, describeResult(getResultCode()));
                        }
                        sentParts.countDown();
                    }
                };

        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(
                    receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(action));
        }

        try {
            ArrayList<PendingIntent> callbacks = new ArrayList<>();
            for (int index = 0; index < parts.size(); index++) {
                Intent callbackIntent =
                        new Intent(action).setPackage(context.getPackageName());
                callbacks.add(
                        PendingIntent.getBroadcast(
                                context,
                                action.hashCode() + index,
                                callbackIntent,
                                PendingIntent.FLAG_UPDATE_CURRENT
                                        | PendingIntent.FLAG_IMMUTABLE));
            }
            manager.sendMultipartTextMessage(
                    phoneNumber, null, parts, callbacks, null);

            if (!sentParts.await(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("통신사 SMS 발신 확인 시간 초과");
            }
            if (failure.get() != null) {
                throw new IllegalStateException(failure.get());
            }
        } finally {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already removed during process shutdown.
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static SmsManager smsManager() {
        int subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId();
        if (subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return SmsManager.getSmsManagerForSubscriptionId(subscriptionId);
        }
        return SmsManager.getDefault();
    }

    private static String describeResult(int resultCode) {
        switch (resultCode) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                return "통신사 SMS 일반 오류";
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                return "이동통신 서비스 없음";
            case SmsManager.RESULT_ERROR_NULL_PDU:
                return "SMS PDU 생성 실패";
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                return "휴대전화 무선 통신이 꺼져 있음";
            case SmsManager.RESULT_ERROR_LIMIT_EXCEEDED:
                return "기기 SMS 발신 한도 초과";
            default:
                return "SMS 발신 오류 코드 " + resultCode;
        }
    }
}
