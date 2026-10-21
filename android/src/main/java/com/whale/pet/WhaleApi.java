package com.whale.pet;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import okhttp3.*;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

/**
 * 插件 HTTP 接口客户端（/whale-pet/api/*）。
 * 地址由配对时填的 ws(s)://…/whale-pet/ws 推导成 http(s)://…/whale-pet。
 */
public final class WhaleApi {
    public interface Cb { void done(JSONObject body, String error); }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WhaleApi() {}

    /** ws://host:28000/whale-pet/ws → http://host:28000/whale-pet */
    public static String base(SharedPreferences prefs) {
        String u = prefs.getString("bridge-url", "");
        if (u == null) u = "";
        u = u.trim();
        if (u.isEmpty()) return null;
        if (u.startsWith("wss://")) u = "https://" + u.substring(6);
        else if (u.startsWith("ws://")) u = "http://" + u.substring(5);
        else if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        int cut = u.indexOf("/whale-pet");
        if (cut > 0) u = u.substring(0, cut) + "/whale-pet";
        else {
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            u = u + "/whale-pet";
        }
        return u;
    }

    public static void listSessions(SharedPreferences prefs, Cb cb) {
        call(prefs, "GET", "/api/sessions", null, cb);
    }

    public static void session(SharedPreferences prefs, String id, int limit, Long before, Cb cb) {
        String q = "?limit=" + limit + (before == null ? "" : "&before=" + before);
        call(prefs, "GET", "/api/sessions/" + enc(id) + q, null, cb);
    }

    public static void send(SharedPreferences prefs, String id, String text, Cb cb) {
        JSONObject body = new JSONObject();
        try { body.put("text", text); } catch (Exception ignored) {}
        call(prefs, "POST", "/api/sessions/" + enc(id) + "/message", body, cb);
    }

    public static void stop(SharedPreferences prefs, String id, Cb cb) {
        call(prefs, "POST", "/api/sessions/" + enc(id) + "/stop", new JSONObject(), cb);
    }

    private static void call(SharedPreferences prefs, String method, String path, JSONObject body, Cb cb) {
        final String base = base(prefs);
        if (base == null) { MAIN.post(() -> cb.done(null, "还没填服务器地址（设置 → 连接鲸宝）")); return; }
        final String token = prefs.getString("bridge-token", "");
        Request.Builder b = new Request.Builder().url(base + path).header("x-whale-token", token == null ? "" : token);
        if ("POST".equals(method)) b.post(RequestBody.create(body == null ? "{}" : body.toString(), JSON));
        else b.get();
        CLIENT.newCall(b.build()).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                final String msg = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
                MAIN.post(() -> cb.done(null, msg));
            }
            @Override public void onResponse(Call call, Response r) throws IOException {
                String raw = "";
                try { raw = r.body() == null ? "" : r.body().string(); } catch (Exception ignored) {}
                JSONObject obj = null;
                String err = null;
                try { obj = new JSONObject(raw); } catch (Exception e) { err = "HTTP " + r.code(); }
                if (obj != null) {
                    if (!obj.optBoolean("ok", false)) err = obj.optString("message", "HTTP " + r.code());
                    else if (!r.isSuccessful()) err = "HTTP " + r.code();
                }
                final JSONObject fo = obj;
                final String fe = err;
                MAIN.post(() -> cb.done(fo, fe));
            }
        });
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }
}
