package com.whale.pet;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 任务页（原生）：电脑端 DSH 的会话列表 + 会话详情 + 发消息 + 中止。
 * 数据全部来自插件只读/可控接口 /whale-pet/api/*，不再依赖 WebView。
 */
final class TasksPage {
    interface Host { void toast(String text); void openSettings(); }

    private final Activity a;
    private final PetStore store;
    private final Ui.Palette p;
    private final Host host;
    private final FrameLayout root;
    private LinearLayout listBox;
    private TextView listHint;
    private FrameLayout detailLayer;
    private LinearLayout msgBox;
    private ScrollView detailScroll;
    private TextView detailTitle, detailStatus;
    private EditText input;
    private LinearLayout sendRow;
    private String openId, openTitle;
    private int openTotal;
    private boolean active, loading, sending;
    private final Handler h = new Handler(Looper.getMainLooper());

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (openId == null) loadList(); else loadDetail(true);
            h.postDelayed(this, 4000);
        }
    };

    TasksPage(Activity activity, PetStore store, Ui.Palette palette, Host host) {
        this.a = activity; this.store = store; this.p = palette; this.host = host;
        this.root = new FrameLayout(activity);
        this.root.setBackgroundColor(palette.bg);
        buildList();
        buildDetail();
    }

    View view() { return root; }

    void resume() { active = true; h.removeCallbacks(poll); h.post(poll); }
    void pause() { active = false; h.removeCallbacks(poll); }

    /** 返回键：详情页退回列表。 */
    boolean back() {
        if (detailLayer.getVisibility() == View.VISIBLE) { closeDetail(); return true; }
        return false;
    }

    void refresh() { if (openId == null) loadList(); else loadDetail(false); }

    // ---------------- 列表 ----------------

    private void buildList() {
        LinearLayout page = Ui.column(a);
        LinearLayout head = Ui.row(a);
        head.setPadding(dp(20), dp(16), dp(20), dp(8));
        LinearLayout titles = Ui.column(a);
        Ui.add(titles, Ui.text(a, "TASKS / 电脑上的 DEEPSEEK", 9, p.muted, true), -2, 0);
        Ui.add(titles, Ui.text(a, "任务", 26, p.ink, true), -2, 4);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView rf = Ui.button(a, "刷新", p, this::loadList);
        rf.setPadding(dp(14), dp(8), dp(14), dp(8));
        head.addView(rf, new LinearLayout.LayoutParams(-2, -2));
        page.addView(head, new LinearLayout.LayoutParams(-1, -2));

        listHint = Ui.text(a, "", 11, p.muted, false);
        listHint.setPadding(dp(22), 0, dp(22), dp(6));
        page.addView(listHint, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        listBox = Ui.column(a);
        listBox.setPadding(dp(14), 0, dp(14), dp(24));
        scroll.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
    }

    private void loadList() {
        if (loading) return;
        loading = true;
        listHint.setText("正在读取电脑端任务…");
        WhaleApi.listSessions(store.prefs, (body, err) -> {
            loading = false;
            if (err != null || body == null) {
                String msg = err == null ? "无响应" : err;
                listHint.setText("");
                listBox.removeAllViews();
                LinearLayout card = Ui.column(a);
                card.setBackground(Ui.round(p.card, p.line, 18, a));
                card.setPadding(dp(18), dp(16), dp(18), dp(16));
                Ui.add(card, Ui.text(a, "读不到电脑上的任务", 15, p.ink, true), -2, 0);
                Ui.add(card, Ui.text(a, msg, 12, p.muted, false), -2, 8);
                boolean noUrl = msg.contains("服务器地址");
                if (noUrl) {
                    Ui.add(card, Ui.text(a, "先在设置里填电脑地址和配对码，连上后这里就会出现电脑上的会话。", 12, p.muted, false), -2, 10);
                    Ui.add(card, Ui.labeled(a, "去设置", "settings", p, host::openSettings), 42, 14);
                } else {
                    Ui.add(card, Ui.labeled(a, "重试", "up", p, this::loadList), 42, 14);
                }
                Ui.add(listBox, card, -2, 8);
                return;
            }
            JSONArray arr = body.optJSONArray("sessions");
            renderList(arr == null ? new JSONArray() : arr);
        });
    }

    private void renderList(JSONArray arr) {
        listBox.removeAllViews();
        List<JSONObject> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.startsWith("whale-")) continue; // 鲸宝自己的聊天会话不列在任务里
            items.add(o);
        }
        // 有标题的排前面，其余按 id 倒序（id 含时间戳的会话自然靠前）
        Collections.sort(items, (x, y) -> {
            long tx = x.optLong("updatedAt", 0), ty = y.optLong("updatedAt", 0);
            if (tx != ty) return Long.compare(ty, tx);
            return y.optString("id", "").compareTo(x.optString("id", ""));
        });
        int live = 0;
        for (JSONObject o : items) if (o.optBoolean("live", false)) live++;
        listHint.setText(items.size() + " 个会话 · " + live + " 个在线");

        if (items.isEmpty()) {
            LinearLayout empty = Ui.column(a);
            empty.setPadding(dp(8), dp(60), dp(8), 0);
            TextView t1 = Ui.text(a, "电脑上还没有任务", 16, p.ink, true);
            t1.setGravity(Gravity.CENTER);
            empty.addView(t1);
            TextView t2 = Ui.text(a, "在电脑端 DeepSeek 里开一个会话，这里就会出现", 12, p.muted, false);
            t2.setGravity(Gravity.CENTER);
            t2.setPadding(0, dp(10), 0, 0);
            empty.addView(t2);
            listBox.addView(empty);
            return;
        }
        for (JSONObject o : items) listBox.addView(row(o));
    }

    private View row(JSONObject o) {
        final String id = o.optString("id", "");
        String title = o.optString("title", "");
        if (title == null || title.isEmpty() || title.equals("null")) title = "空闲会话 · " + (id.startsWith("session-") ? id.substring(8, Math.min(13, id.length())) : id);
        final String fid = id, ftitle = title;
        boolean isLive = o.optBoolean("live", false), busy = o.optBoolean("busy", false);

        LinearLayout card = Ui.column(a);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(Ui.ripple(a, p.card, p.blue, 16));
        card.setClickable(true);

        LinearLayout top = Ui.row(a);
        TextView t = Ui.text(a, title, 15, p.ink, true);
        t.setMaxLines(2);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        TextView dot = Ui.text(a, busy ? "运行中" : isLive ? "在线" : "空闲", 10, busy ? p.blue : isLive ? 0xff2e8b57 : p.muted, busy);
        dot.setPadding(dp(8), dp(3), dp(8), dp(3));
        dot.setBackground(Ui.round(busy ? p.soft : p.card, p.line, 10, a));
        top.addView(dot, new LinearLayout.LayoutParams(-2, -2));
        card.addView(top);

        long ts = o.optLong("updatedAt", 0);
        String when = ts > 0 ? new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts)) : "";
        String cwd = o.optString("cwd", "");
        TextView sub = Ui.text(a, (when.isEmpty() ? "无时间" : when) + (cwd.isEmpty() ? "" : " · " + cwd), 11, p.muted, false);
        sub.setPadding(0, dp(6), 0, 0);
        sub.setMaxLines(1);
        card.addView(sub);

        card.setOnClickListener(v -> open(fid, ftitle));
        Ui.press(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        FrameLayout wrap = new FrameLayout(a);
        wrap.addView(card, new FrameLayout.LayoutParams(-1, -2));
        wrap.setLayoutParams(lp);
        return wrap;
    }

    // ---------------- 详情 ----------------

    private void buildDetail() {
        detailLayer = new FrameLayout(a);
        detailLayer.setBackgroundColor(p.bg);
        detailLayer.setVisibility(View.GONE);

        LinearLayout page = Ui.column(a);
        LinearLayout head = Ui.row(a);
        head.setPadding(dp(12), dp(12), dp(14), dp(8));

        TextView back = Ui.text(a, "‹", 30, p.ink, true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setOnClickListener(v -> closeDetail());
        head.addView(back, new LinearLayout.LayoutParams(-2, dp(44)));

        LinearLayout titles = Ui.column(a);
        detailTitle = Ui.text(a, "", 15, p.ink, true);
        detailTitle.setMaxLines(1);
        detailStatus = Ui.text(a, "", 10, p.muted, false);
        titles.addView(detailTitle);
        titles.addView(detailStatus);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));

        TextView stop = Ui.button(a, "停止", p, this::stopRun);
        stop.setPadding(dp(12), dp(6), dp(12), dp(6));
        head.addView(stop, new LinearLayout.LayoutParams(-2, -2));
        page.addView(head, new LinearLayout.LayoutParams(-1, -2));

        detailScroll = new ScrollView(a);
        detailScroll.setFillViewport(true);
        msgBox = Ui.column(a);
        msgBox.setPadding(dp(14), dp(4), dp(14), dp(14));
        detailScroll.addView(msgBox, new ScrollView.LayoutParams(-1, -2));
        page.addView(detailScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        sendRow = Ui.row(a);
        sendRow.setPadding(dp(12), dp(8), dp(12), dp(10));
        sendRow.setBackgroundColor(p.card);
        input = new EditText(a);
        input.setHint("给这个任务发消息…");
        input.setTextSize(13);
        input.setTextColor(p.ink);
        input.setHintTextColor(p.muted);
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackground(Ui.round(p.soft, p.line, 14, a));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        sendRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        TextView send = Ui.button(a, "发送", p, this::send);
        send.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.setMargins(dp(8), 0, 0, 0);
        sendRow.addView(send, slp);
        page.addView(sendRow, new LinearLayout.LayoutParams(-1, -2));

        detailLayer.addView(page, new FrameLayout.LayoutParams(-1, -1));
        root.addView(detailLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void open(String id, String title) {
        openId = id; openTitle = title; openTotal = 0;
        detailTitle.setText(title);
        detailStatus.setText("读取中…");
        msgBox.removeAllViews();
        detailLayer.setVisibility(View.VISIBLE);
        Ui.enter(detailLayer);
        loadDetail(false);
    }

    private void closeDetail() {
        detailLayer.setVisibility(View.GONE);
        openId = null;
        loadList();
    }

    private void loadDetail(boolean quiet) {
        final String id = openId;
        if (id == null) return;
        if (!quiet) detailStatus.setText("读取中…");
        WhaleApi.session(store.prefs, id, 80, null, (body, err) -> {
            if (!id.equals(openId)) return;
            if (err != null || body == null) { detailStatus.setText("读取失败：" + (err == null ? "无响应" : err)); return; }
            String status = body.optString("status", "idle");
            detailStatus.setText("running".equals(status) ? "运行中 · 每 4 秒刷新" : "空闲 · " + body.optInt("total", 0) + " 条事件");
            JSONArray evs = body.optJSONArray("events");
            if (evs == null) return;
            boolean atBottom = isAtBottom();
            renderMessages(evs, body.optInt("total", 0));
            if (atBottom || !quiet) scrollDown();
            openTotal = body.optInt("total", 0);
        });
    }

    private boolean isAtBottom() {
        if (detailScroll == null) return true;
        View child = detailScroll.getChildAt(0);
        if (child == null) return true;
        return detailScroll.getScrollY() + detailScroll.getHeight() >= child.getHeight() - dp(48);
    }

    private void renderMessages(JSONArray evs, int total) {
        msgBox.removeAllViews();
        if (evs.length() == 0) {
            TextView t = Ui.text(a, "这个会话还没有消息", 12, p.muted, false);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(40), 0, 0);
            msgBox.addView(t);
            return;
        }
        for (int i = 0; i < evs.length(); i++) {
            JSONObject e = evs.optJSONObject(i);
            if (e == null) continue;
            String role = e.optString("role", "");
            String text = e.optString("text", "");
            if ("user".equals(role)) addMsg(text, true, false);
            else if ("assistant".equals(role)) addMsg(text, false, false);
            else if ("tool".equals(role)) addTool(e.optString("name", "tool"), e.optString("args", ""));
            else if ("toolResult".equals(role)) addToolResult(text, e.isNull("error") ? "" : e.optString("error", ""));
            else if ("approval".equals(role)) addApproval(e.optString("name", ""), e.optString("reason", ""));
        }
        if (total > evs.length()) {
            TextView more = Ui.text(a, "（更早的 " + (total - evs.length()) + " 条消息未显示）", 10, p.muted, false);
            more.setGravity(Gravity.CENTER);
            more.setPadding(0, 0, 0, dp(8));
            msgBox.addView(more);
        }
    }

    private void addMsg(String text, boolean mine, boolean streaming) {
        if (text == null || text.isEmpty()) return;
        LinearLayout line = Ui.row(a);
        line.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
        TextView bubble = Ui.text(a, text, 13, mine ? Color.WHITE : p.ink, false);
        bubble.setLineSpacing(dp(3), 1f);
        bubble.setPadding(dp(13), dp(10), dp(13), dp(10));
        bubble.setBackground(Ui.round(mine ? p.blue : p.card, mine ? 0 : p.line, 16, a));
        bubble.setTextIsSelectable(true);
        int maxW = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.78f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        bubble.setMaxWidth(maxW);
        line.addView(bubble, lp);
        msgBox.addView(line);
    }

    private void addTool(String name, String args) {
        String s = "· " + name + (args.isEmpty() ? "" : "  " + (args.length() > 120 ? args.substring(0, 120) + "…" : args));
        TextView t = Ui.text(a, s, 10, p.muted, false);
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        t.setMaxLines(3);
        msgBox.addView(t);
    }

    private void addToolResult(String text, String error) {
        String s = error != null && !error.isEmpty() ? "✗ " + error : "↳ " + (text == null ? "" : text.replace("\n", " "));
        if (s.length() > 220) s = s.substring(0, 220) + "…";
        TextView t = Ui.text(a, s, 10, error != null && !error.isEmpty() ? 0xffcc4444 : p.muted, false);
        t.setPadding(dp(10), dp(2), dp(6), dp(4));
        t.setMaxLines(3);
        msgBox.addView(t);
    }

    private void addApproval(String tool, String reason) {
        TextView t = Ui.text(a, "需要确认：" + tool + (reason.isEmpty() ? "" : " · " + reason) + "（在电脑上点允许）", 11, 0xffb8860b, false);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setBackground(Ui.round(p.soft, p.line, 10, a));
        msgBox.addView(t);
    }

    private void scrollDown() {
        detailScroll.post(() -> detailScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void send() {
        final String id = openId;
        final String text = input.getText().toString().trim();
        if (id == null || text.isEmpty() || sending) return;
        sending = true;
        detailStatus.setText("发送中…");
        WhaleApi.send(store.prefs, id, text, (body, err) -> {
            sending = false;
            if (err != null) { detailStatus.setText("发送失败：" + err); host.toast("发送失败：" + err); return; }
            input.setText("");
            detailStatus.setText("已发送，等待回复…");
            h.postDelayed(() -> loadDetail(true), 700);
        });
    }

    private void stopRun() {
        final String id = openId;
        if (id == null) return;
        WhaleApi.stop(store.prefs, id, (body, err) -> {
            if (err != null) { host.toast("中止失败：" + err); return; }
            host.toast("已中止");
            h.postDelayed(() -> loadDetail(true), 500);
        });
    }

    private int dp(float n) { return Ui.dp(a, n); }
}
