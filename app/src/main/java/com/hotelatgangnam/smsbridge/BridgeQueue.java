package com.hotelatgangnam.smsbridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;
import com.hotelatgangnam.smsbridge.core.MessageFingerprint;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Device-local durable queue written before acknowledging either Android or Slack events. */
final class BridgeQueue extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "bridge_queue.db";
    private static final int DATABASE_VERSION = 1;

    BridgeQueue(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE pending_events ("
                        + "id TEXT PRIMARY KEY NOT NULL,"
                        + "kind TEXT NOT NULL,"
                        + "payload TEXT NOT NULL,"
                        + "created_at INTEGER NOT NULL,"
                        + "attempts INTEGER NOT NULL DEFAULT 0)");
        database.execSQL(
                "CREATE INDEX pending_events_created "
                        + "ON pending_events(created_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unexpected bridge queue schema upgrade");
    }

    synchronized boolean enqueueInbound(BridgeProcessor.InboundSms message) {
        String fingerprint = MessageFingerprint.inboundSms(
                message.sender,
                message.body,
                message.providerTimestampMillis,
                message.subscriptionId);
        JSONObject payload = new JSONObject();
        try {
            payload.put("sender", message.sender);
            payload.put("body", message.body);
            payload.put("providerTimestamp", message.providerTimestampMillis);
            payload.put("subscriptionId", message.subscriptionId);
        } catch (JSONException impossible) {
            return false;
        }
        return insertOrAlreadyPresent("inbound:" + fingerprint, "inbound", payload);
    }

    synchronized boolean enqueueSlack(BridgeProcessor.SlackMessage message) {
        // A thread-broadcast can produce more than one Slack envelope/event_id for one message.
        // channel+ts is the canonical identity of the human-authored Slack message.
        String stableId = message.channelId + ":" + message.timestamp;
        JSONObject payload = new JSONObject();
        try {
            payload.put("eventId", message.eventId);
            payload.put("channelId", message.channelId);
            payload.put("userId", message.userId);
            payload.put("text", message.text);
            payload.put("timestamp", message.timestamp);
            payload.put("threadTimestamp", message.threadTimestamp);
        } catch (JSONException impossible) {
            return false;
        }
        return insertOrAlreadyPresent("slack:" + stableId, "slack", payload);
    }

    synchronized List<PendingEvent> readBatch(int limit) {
        List<PendingEvent> events = new ArrayList<>();
        try (Cursor cursor =
                getReadableDatabase()
                        .query(
                                "pending_events",
                                new String[] {"id", "kind", "payload", "attempts"},
                                null,
                                null,
                                null,
                                null,
                                "created_at ASC",
                                Integer.toString(Math.max(1, limit)))) {
            while (cursor.moveToNext()) {
                try {
                    events.add(
                            new PendingEvent(
                                    cursor.getString(0),
                                    cursor.getString(1),
                                    new JSONObject(cursor.getString(2)),
                                    cursor.getInt(3)));
                } catch (JSONException corruptPayload) {
                    remove(cursor.getString(0));
                }
            }
        }
        return events;
    }

    synchronized void remove(String id) {
        getWritableDatabase().delete(
                "pending_events", "id = ?", new String[] {id});
    }

    synchronized void recordAttempt(String id) {
        getWritableDatabase()
                .execSQL(
                        "UPDATE pending_events SET attempts = attempts + 1 WHERE id = ?",
                        new Object[] {id});
    }

    synchronized int size() {
        try (Cursor cursor =
                getReadableDatabase()
                        .rawQuery("SELECT COUNT(*) FROM pending_events", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    private boolean insertOrAlreadyPresent(
            String id, String kind, JSONObject payload) {
        SQLiteDatabase database = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("kind", kind);
        values.put("payload", payload.toString());
        values.put("created_at", System.currentTimeMillis());
        long inserted =
                database.insertWithOnConflict(
                        "pending_events",
                        null,
                        values,
                        SQLiteDatabase.CONFLICT_IGNORE);
        return inserted != -1 || exists(database, id);
    }

    private static boolean exists(SQLiteDatabase database, String id) {
        try (Cursor cursor =
                database.rawQuery(
                        "SELECT 1 FROM pending_events WHERE id = ? LIMIT 1",
                        new String[] {id})) {
            return cursor.moveToFirst();
        }
    }

    static final class PendingEvent {
        final String id;
        final String kind;
        final JSONObject payload;
        final int attempts;

        PendingEvent(String id, String kind, JSONObject payload, int attempts) {
            this.id = id;
            this.kind = kind;
            this.payload = payload;
            this.attempts = attempts;
        }

        BridgeProcessor.InboundSms asInbound() throws JSONException {
            return new BridgeProcessor.InboundSms(
                    payload.getString("sender"),
                    payload.getString("body"),
                    payload.getLong("providerTimestamp"),
                    payload.getInt("subscriptionId"));
        }

        BridgeProcessor.SlackMessage asSlack() throws JSONException {
            return new BridgeProcessor.SlackMessage(
                    nullIfJsonNull("eventId"),
                    payload.getString("channelId"),
                    payload.getString("userId"),
                    payload.getString("text"),
                    payload.getString("timestamp"),
                    nullIfJsonNull("threadTimestamp"));
        }

        private String nullIfJsonNull(String key) {
            return payload.isNull(key) ? null : payload.optString(key, null);
        }
    }
}
