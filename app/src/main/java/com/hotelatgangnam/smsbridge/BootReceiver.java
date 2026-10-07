package com.hotelatgangnam.smsbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts an explicitly enabled bridge after the phone finishes booting. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !new ConfigStore(context).isEnabled()) {
            return;
        }
        try {
            BridgeService.start(context);
        } catch (RuntimeException error) {
            Log.e("HotelSmsBridge", "Bridge could not restart after boot", error);
        }
    }
}
