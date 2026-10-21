package com.whale.pet;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 泡泡鲸 ↔ DSH 桥接客户端（P0 纯文字版）。
 * 协议：hello/token 握手 → user.text → agent.delta 流式 → agent.done / agent.error。
 */
public final class WhaleBridge {
    public interface Listener {
        void onReady(String model);
        void onDelta(String delta);
        void onDone(String fullText);
        void onError(String message);
        void onDisconnected();
        void onDisconnectReason(String reason);
    }

    private static final String TAG = "WhaleBridge";
    private static final long RECONNECT_DELAY_MS = 5000;

    private final String url;
    private final String token;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WebSocket ws;
    private OkHttpClient client;
    private volatile boolean shouldRun = false;
    private volatile boolean ready = false;
    private int msgSeq = 0;

    public WhaleBridge(String url, String token, Listener listener) {
        this.url = url;
        this.token = token;
        this.listener = listener;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    public void connect() {
        if (ws != null) return;
        shouldRun = true;
        Request req = new Request.Builder().url(url).build();
        ws = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                send(safeJson("type", "hello", "token", token));
            }
            @Override public void onMessage(WebSocket webSocket, String text) { handleMessage(text); }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { handleDisconnect("closed"); }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                String msg = t != null ? t.getClass().getSimpleName() + ": " + t.getMessage() : "failure";
                if (response != null) msg += " (HTTP " + response.code() + ")";
                handleDisconnect(msg);
            }
        });
    }

    public void sendText(String text) {
        if (!ready) {
            main.post(() -> listener.onError("还没连上服务器，稍等一下"));
            return;
        }
        send(safeJson("type", "user.text", "text", text, "id", "m" + (++msgSeq)));
    }

    public boolean isReady() { return ready; }

    public void close() {
        shouldRun = false;
        ready = false;
        WebSocket w = ws;
        ws = null;
        if (w != null) w.close(1000, "bye");
        if (client != null) client.dispatcher().executorService().shutdown();
    }

    private void handleMessage(String raw) {
        JSONObject o = parse(raw);
        if (o == null) return;
        String type = o.optString("type");
        switch (type) {
            case "hello.ok":
                ready = true;
                main.post(() -> listener.onReady(o.optString("model")));
                break;
            case "hello.fail":
                shouldRun = false; // token 错，重连也没用
                main.post(() -> listener.onError("配对码不对"));
                break;
            case "agent.delta":
                main.post(() -> listener.onDelta(o.optString("delta")));
                break;
            case "agent.done":
                final String full = o.optString("text");
                main.post(() -> listener.onDone(full));
                break;
            case "agent.error":
                main.post(() -> listener.onError(o.optString("message", "出错了")));
                break;
            default:
                break;
        }
    }

    private void handleDisconnect(String reason) {
        ws = null;
        ready = false;
        Log.w(TAG, "disconnected: " + reason);
        main.post(() -> { listener.onDisconnected(); listener.onDisconnectReason(reason); });
        if (shouldRun) {
            main.postDelayed(() -> { if (shouldRun && ws == null) connect(); }, RECONNECT_DELAY_MS);
        }
    }

    private void send(String json) {
        WebSocket w = ws;
        if (w != null) w.send(json);
    }

    private static JSONObject parse(String raw) {
        try { return new JSONObject(raw); } catch (JSONException e) { return null; }
    }

    private static String safeJson(String... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try { o.put(kv[i], kv[i + 1]); } catch (JSONException ignored) {}
        }
        return o.toString();
    }
}
