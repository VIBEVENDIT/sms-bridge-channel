package com.hotelatgangnam.smsbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;

/** Receives phone SMS broadcasts and persists them before starting network work. */
public final class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "HotelSmsBridge";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())
                || !new ConfigStore(context).isEnabled()) {
            return;
        }

        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) {
            return;
        }
        String sender = parts[0].getDisplayOriginatingAddress();
        StringBuilder body = new StringBuilder();
        long providerTimestamp = Long.MAX_VALUE;
        for (SmsMessage part : parts) {
            if (part.getDisplayMessageBody() != null) {
                body.append(part.getDisplayMessageBody());
            }
            providerTimestamp = Math.min(providerTimestamp, part.getTimestampMillis());
        }
        if (providerTimestamp == Long.MAX_VALUE) {
            providerTimestamp = System.currentTimeMillis();
        }
        int subscriptionId = intent.getIntExtra("subscription", -1);
        BridgeProcessor.InboundSms inbound =
                new BridgeProcessor.InboundSms(
                        sender, body.toString(), providerTimestamp, subscriptionId);

        try (BridgeQueue queue = new BridgeQueue(context)) {
            if (queue.enqueueInbound(inbound)) {
                BridgeService.requestDrain(context);
            } else {
                Log.e(TAG, "Unable to persist inbound SMS; bridge not started");
            }
        }
    }
}
