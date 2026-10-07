package com.hotelatgangnam.smsbridge;

import com.hotelatgangnam.smsbridge.core.BridgeProcessor;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Slack Socket Mode receiver. Relevant events are persisted before their envelope is acknowledged. */
final class SlackSocketClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    interface Listener {
        boolean persist(BridgeProcessor.SlackMessage message);

        void onConnected();

        void onDisconnected(String reason);
    }

    private final OkHttpClient http;
    private final String appToken;
    private final String channelId;
    private final Listener listener;

    private volatile WebSocket socket;
    private volatile boolean explicitlyClosed;
    private volatile boolean connectedOrConnecting;

    SlackSocketClient(
            OkHttpClient http, String appToken, String channelId, Listener listener) {
        this.http = http;
        this.appToken = appToken;
        this.channelId = channelId;
        this.listener = listener;
    }

    synchronized void connect() throws IOException, JSONException {
        if (connectedOrConnecting) {
            return;
        }
        connectedOrConnecting = true;
        explicitlyClosed = false;
        try {
            Request openRequest = new Request.Builder()
                    .url("https://slack.com/api/apps.connections.open")
                    .header("Authorization", "Bearer " + appToken)
                    .post(RequestBody.create("{}", JSON))
                    .build();
            String socketUrl;
            try (Response response = http.newCall(openRequest).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new IOException("Slack Socket HTTP " + response.code());
                }
                JSONObject result = new JSONObject(response.body().string());
                if (!result.optBoolean("ok")) {
                    throw new IOException(
                            "Slack Socket API: "
                                    + result.optString("error", "unknown_error"));
                }
                socketUrl = result.getString("url");
            }

            socket =
                    http.newWebSocket(
                        new Request.Builder().url(socketUrl).build(),
                        new WebSocketListener() {
                            @Override
                            public void onOpen(WebSocket webSocket, Response response) {
                                listener.onConnected();
                            }

                            @Override
                            public void onMessage(WebSocket webSocket, String text) {
                                handleEnvelope(webSocket, text);
                            }

                            @Override
                            public void onClosing(
                                    WebSocket webSocket, int code, String reason) {
                                webSocket.close(code, reason);
                            }

                            @Override
                            public void onClosed(
                                    WebSocket webSocket, int code, String reason) {
                                connectedOrConnecting = false;
                                if (!explicitlyClosed) {
                                    listener.onDisconnected("socket_closed_" + code);
                                }
                            }

                            @Override
                            public void onFailure(
                                    WebSocket webSocket, Throwable error, Response response) {
                                connectedOrConnecting = false;
                                if (!explicitlyClosed) {
                                    listener.onDisconnected("socket_failure");
                                }
                            }
                            }
                        });
        } catch (IOException | JSONException error) {
            connectedOrConnecting = false;
            throw error;
        }
    }

    synchronized void close() {
        explicitlyClosed = true;
        connectedOrConnecting = false;
        WebSocket active = socket;
        socket = null;
        if (active != null) {
            active.close(1000, "bridge_stopped");
        }
    }

    private void handleEnvelope(WebSocket webSocket, String text) {
        try {
            JSONObject envelope = new JSONObject(text);
            String type = envelope.optString("type");
            if ("disconnect".equals(type)) {
                acknowledge(webSocket, envelope.optString("envelope_id", null));
                webSocket.close(1000, "slack_refresh_requested");
                if (!explicitlyClosed) {
                    listener.onDisconnected(
                            "slack_" + envelope.optString("reason", "disconnect"));
                }
                return;
            }

            String envelopeId = envelope.optString("envelope_id", null);
            if (!"events_api".equals(type)) {
                acknowledge(webSocket, envelopeId);
                return;
            }

            JSONObject payload = envelope.optJSONObject("payload");
            JSONObject event = payload == null ? null : payload.optJSONObject("event");
            if (!isRelevantHumanMessage(event)) {
                acknowledge(webSocket, envelopeId);
                return;
            }

            BridgeProcessor.SlackMessage message =
                    new BridgeProcessor.SlackMessage(
                            payload.optString("event_id", null),
                            event.optString("channel"),
                            event.optString("user"),
                            event.optString("text"),
                            event.optString("ts"),
                            event.optString("thread_ts", null));
            if (listener.persist(message)) {
                acknowledge(webSocket, envelopeId);
            }
        } catch (JSONException ignored) {
            // Invalid envelopes are not acknowledged, allowing Slack to retry or disconnect.
        }
    }

    private boolean isRelevantHumanMessage(JSONObject event) {
        if (event == null
                || !"message".equals(event.optString("type"))
                || !channelId.equals(event.optString("channel"))
                || event.optString("user").trim().isEmpty()
                || event.has("bot_id")
                || event.has("app_id")) {
            return false;
        }
        String subtype = event.optString("subtype");
        return subtype.trim().isEmpty() || "thread_broadcast".equals(subtype);
    }

    private static void acknowledge(WebSocket webSocket, String envelopeId) {
        if (envelopeId == null || envelopeId.trim().isEmpty()) {
            return;
        }
        try {
            webSocket.send(new JSONObject().put("envelope_id", envelopeId).toString());
        } catch (JSONException ignored) {
            // A plain string envelope id cannot normally fail JSON encoding.
        }
    }
}
