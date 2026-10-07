package com.hotelatgangnam.smsbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;
import com.hotelatgangnam.smsbridge.core.BridgeSettings;

import org.json.JSONException;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;

/** Persistent phone-side bridge runtime. No server or PC companion is involved. */
public final class BridgeService extends Service {
    private static final String TAG = "HotelSmsBridge";
    private static final String CHANNEL_ID = "hotel_sms_bridge_status";
    private static final int NOTIFICATION_ID = 8107;
    private static final String ACTION_START =
            "com.hotelatgangnam.smsbridge.action.START";
    private static final String ACTION_DRAIN =
            "com.hotelatgangnam.smsbridge.action.DRAIN";
    private static final String ACTION_RELOAD =
            "com.hotelatgangnam.smsbridge.action.RELOAD";
    private static final String ACTION_STOP =
            "com.hotelatgangnam.smsbridge.action.STOP";

    private ScheduledExecutorService worker;
    private OkHttpClient http;
    private BridgeQueue queue;
    private ConfigStore configStore;
    private BridgeProcessor processor;
    private ConfigStore.Snapshot activeConfig;
    private SlackSocketClient socket;
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile long reconnectDelaySeconds = 2;
    private volatile boolean stopping;

    static void start(Context context) {
        launch(context, ACTION_START);
    }

    static void reload(Context context) {
        launch(context, ACTION_RELOAD);
    }

    static void requestDrain(Context context) {
        launch(context, ACTION_DRAIN);
    }

    static void stop(Context context) {
        context.startService(
                new Intent(context, BridgeService.class).setAction(ACTION_STOP));
    }

    private static void launch(Context context, String action) {
        Intent intent = new Intent(context, BridgeService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startAsForeground("시작 중 · 저장된 이벤트 확인");
        worker = Executors.newSingleThreadScheduledExecutor();
        http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
        queue = new BridgeQueue(this);
        configStore = new ConfigStore(this);
        worker.scheduleWithFixedDelay(this::safeDrain, 10, 30, TimeUnit.SECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            configStore.setEnabled(false);
            stopping = true;
            stopSelf();
            return START_NOT_STICKY;
        }

        worker.execute(
                () -> {
                    if (ACTION_RELOAD.equals(action) || activeConfig == null) {
                        configure();
                    } else {
                        ensureConnected();
                    }
                    safeDrain();
                });
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        if (socket != null) {
            socket.close();
        }
        if (worker != null) {
            worker.shutdownNow();
        }
        if (queue != null) {
            queue.close();
        }
        if (http != null) {
            http.dispatcher().executorService().shutdown();
            http.connectionPool().evictAll();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void configure() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
        reconnectScheduled.set(false);
        reconnectDelaySeconds = 2;

        try {
            ConfigStore.Snapshot snapshot = configStore.read();
            if (!snapshot.enabled) {
                updateNotification("중지됨");
                stopSelf();
                return;
            }
            if (!snapshot.isReady()) {
                updateNotification("설정 오류 · Slack 토큰과 채널 ID 확인");
                return;
            }
            if (!hasSmsPermissions()) {
                updateNotification("권한 오류 · SMS 수신/발신 권한 필요");
                return;
            }

            activeConfig = snapshot;
            SlackApiClient slackApi =
                    new SlackApiClient(http, snapshot.botToken, snapshot.channelId);
            processor =
                    new BridgeProcessor(
                            new AndroidEventLedger(this),
                            new AndroidSmsSender(this),
                            slackApi);
            socket =
                    new SlackSocketClient(
                            http,
                            snapshot.appToken,
                            snapshot.channelId,
                            new SlackSocketClient.Listener() {
                                @Override
                                public boolean persist(BridgeProcessor.SlackMessage message) {
                                    boolean persisted = queue.enqueueSlack(message);
                                    if (persisted) {
                                        worker.execute(BridgeService.this::safeDrain);
                                    }
                                    return persisted;
                                }

                                @Override
                                public void onConnected() {
                                    reconnectDelaySeconds = 2;
                                    reconnectScheduled.set(false);
                                    updateNotification(
                                            "연결됨 · 대기 "
                                                    + queue.size()
                                                    + "건");
                                    worker.execute(BridgeService.this::safeDrain);
                                }

                                @Override
                                public void onDisconnected(String reason) {
                                    scheduleReconnect(reason);
                                }
                            });
            ensureConnected();
        } catch (RuntimeException error) {
            Log.e(TAG, "Bridge configuration failed", error);
            updateNotification("설정 로드 실패 · 앱에서 다시 저장");
        }
    }

    private void ensureConnected() {
        if (socket == null || stopping || activeConfig == null) {
            return;
        }
        try {
            socket.connect();
            updateNotification("Slack 연결 중 · 대기 " + queue.size() + "건");
        } catch (Exception error) {
            scheduleReconnect("connect_failed");
        }
    }

    private void scheduleReconnect(String reason) {
        if (stopping || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = reconnectDelaySeconds;
        reconnectDelaySeconds = Math.min(120, reconnectDelaySeconds * 2);
        updateNotification("재연결 대기 " + delay + "초 · 이벤트 " + queue.size() + "건");
        try {
            worker.schedule(
                    () -> {
                        reconnectScheduled.set(false);
                        ensureConnected();
                    },
                    delay,
                    TimeUnit.SECONDS);
        } catch (RuntimeException ignored) {
            Log.d(TAG, "Reconnect skipped while service is stopping: " + reason);
        }
    }

    private void safeDrain() {
        try {
            drainQueue();
        } catch (RuntimeException error) {
            Log.e(TAG, "Queue drain failed", error);
        }
    }

    private void drainQueue() {
        if (processor == null || activeConfig == null || !activeConfig.enabled) {
            return;
        }
        BridgeSettings settings = activeConfig.toBridgeSettings();
        List<BridgeQueue.PendingEvent> events = queue.readBatch(50);
        for (BridgeQueue.PendingEvent event : events) {
            BridgeProcessor.Outcome outcome;
            try {
                if ("inbound".equals(event.kind)) {
                    outcome =
                            processor.handleInbound(
                                    event.asInbound(), settings, System.currentTimeMillis());
                } else if ("slack".equals(event.kind)) {
                    outcome =
                            processor.handleSlack(
                                    event.asSlack(), settings, System.currentTimeMillis());
                } else {
                    queue.remove(event.id);
                    continue;
                }
            } catch (JSONException invalidPayload) {
                queue.remove(event.id);
                continue;
            }

            if (outcome.kind == BridgeProcessor.Outcome.Kind.RETRY) {
                queue.recordAttempt(event.id);
                scheduleReconnect(outcome.reason);
                break;
            }
            queue.remove(event.id);
        }
        updateNotification("실행 중 · 대기 " + queue.size() + "건");
    }

    private boolean hasSmsPermissions() {
        return checkSelfPermission(Manifest.permission.RECEIVE_SMS)
                        == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.SEND_SMS)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notification_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("SMS와 Slack 연결 상태 및 미처리 이벤트 수");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private void startAsForeground(String status) {
        Notification notification = notification(status);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String status) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, notification(status));
    }

    private Notification notification(String status) {
        PendingIntent openApp =
                PendingIntent.getActivity(
                        this,
                        0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopBridge =
                PendingIntent.getService(
                        this,
                        1,
                        new Intent(this, BridgeService.class).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_running))
                .setContentText(status)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(
                        new Notification.Action.Builder(
                                        null, "중지", stopBridge)
                                .build())
                .build();
    }
}
