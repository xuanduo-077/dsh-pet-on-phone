package com.whale.pet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 泡泡鲸 App 外壳（v22 全新重写，无 WebView）。
 * root(系统栏/键盘 inset) ├ app bar ├ content(任务/陪伴/互动/足迹/设置) └ bottom nav。
 * 键盘兼容：adjustResize + 全版本 IME inset（API 28 也留 bottom padding）。
 */
public final class MainActivity extends Activity implements PetEngine.Host {

    private static final String[] TAB_NAMES = {"任务", "陪伴", "互动", "足迹", "设置"};
    private static final String[] TAB_ICONS = {"journal", "chat", "heart", "kite", "settings"};
    private static final String[] PAGE_TITLES = {"任务", "陪伴", "互动", "足迹", "设置"};

    private PetStore store;
    private PetCatalog catalog;
    private PetEngine engine;
    private Ui.Palette palette;
    private TasksPage tasks;
    public static volatile boolean visible; // App 是否在前台（PetService 用来暂停桌宠绘制）
    private boolean resumed, pendingStart;
    private int page = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private LinearLayout root, navBar, barActions, chatList;
    private FrameLayout content;
    private TextView barTitle, statusLine;
    private LinearLayout[] tabCells;
    private ImageView[] tabIcons;
    private TextView[] tabLabels;

    private ScrollView chatScroll;
    private EditText chatInput, urlIn, tokenIn;
    private int chatSeen = -1, streamSeen = -1;
    private boolean micHeld;

    private int dp(float v) { return Ui.dp(this, v); }

    // ================= 生命周期 =================
    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PetStore(this);
        catalog = new PetCatalog(this);
        engine = new PetEngine(store, catalog, this);
        palette = new Ui.Palette(store.dark());
        if (saved != null) page = saved.getInt("page", 0);
        buildShell();
        if (store.prefs.getString("bridge-url", "").trim().isEmpty()) page = 4;
        renderPage();
        applyBarTint();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", page);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        visible = true;
        engine.resume();
        if (PetService.instance != null) PetService.instance.updateVisibility();
        handler.post(refreshTick);
        handler.post(chatTick);
        if (pendingStart) {
            pendingStart = false;
            if (Settings.canDrawOverlays(this)) startPet();
        } else if (store.prefs.getBoolean("enabled", false) && PetService.instance == null) {
            startPet();
        }
        if (page == 0 && tasks != null) tasks.resume();
        if (page == 1) syncChatNow();
    }

    @Override protected void onPause() {
        resumed = false;
        visible = false;
        engine.pause();
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(chatTick);
        if (tasks != null) tasks.pause();
        super.onPause();
        if (PetService.instance != null) PetService.instance.updateVisibility();
    }

    @Override public void onBackPressed() {
        if (page != 0) { page = 0; renderPage(); return; }
        if (tasks != null && tasks.back()) return;
        moveTaskToBack(true);
    }

    // ================= 外壳 =================
    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.bg);
        setContentView(root);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, left, right, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
                left = insets.getInsets(WindowInsets.Type.statusBars()).left;
                right = insets.getInsets(WindowInsets.Type.statusBars()).right;
                int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                bottom = Math.max(nav, ime);
            } else {
                top = insets.getSystemWindowInsetTop();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(left, top, right, 0);
            if (navBar != null) navBar.setPadding(0, 0, 0, bottom);
            return insets;
        });

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(12), dp(8));
        barTitle = new TextView(this);
        barTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        barTitle.setTextColor(palette.ink);
        barTitle.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(barTitle, new LinearLayout.LayoutParams(0, -2, 1));
        barActions = new LinearLayout(this);
        barActions.setOrientation(LinearLayout.HORIZONTAL);
        barActions.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(barActions, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        buildNavBar();
    }

    private void buildNavBar() {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(palette.card);
        View divider = new View(this);
        divider.setBackgroundColor(palette.line);
        navBar.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        int n = TAB_NAMES.length;
        tabCells = new LinearLayout[n];
        tabIcons = new ImageView[n];
        tabLabels = new TextView[n];
        for (int i = 0; i < n; i++) {
            final int target = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            ImageView ic = new ImageView(this);
            ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView lb = new TextView(this);
            lb.setText(TAB_NAMES[i]);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            lb.setGravity(Gravity.CENTER);
            lb.setIncludeFontPadding(true);
            lb.setPadding(0, dp(3), 0, 0);
            cell.addView(lb, new LinearLayout.LayoutParams(-2, -2));
            cell.setOnClickListener(v -> { if (page != target) { page = target; renderPage(); } });
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(52), 1));
            tabCells[i] = cell; tabIcons[i] = ic; tabLabels[i] = lb;
        }
        navBar.addView(row, new LinearLayout.LayoutParams(-1, -2));
        root.addView(navBar, new LinearLayout.LayoutParams(-1, -2));
        styleTabs();
    }


    private void styleTabs() {
        for (int i = 0; i < tabCells.length; i++) {
            boolean on = page == i;
            tabIcons[i].setImageResource(iconRes(TAB_ICONS[i]));
            tabIcons[i].setColorFilter(on ? palette.blue : palette.muted);
            tabLabels[i].setTextColor(on ? palette.blue : palette.muted);
            tabLabels[i].setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void applyBarTint() {
        getWindow().setStatusBarColor(palette.bg);
        getWindow().setNavigationBarColor(palette.card);
        int flags = palette.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private int iconRes(String name) {
        return getResources().getIdentifier("ui_" + name, "drawable", getPackageName());
    }

    // ================= 页面调度 =================
    private void renderPage() {
        if (tasks != null) tasks.pause();
        content.removeAllViews();
        barActions.removeAllViews();
        barTitle.setText(PAGE_TITLES[page]);
        switch (page) {
            case 0: pageTasks(); break;
            case 1: pageChat(); break;
            case 2: pagePet(); break;
            case 3: pageJournal(); break;
            default: pageSettings();
        }
        styleTabs();
        if (page >= 2) engine.resume(); else engine.pause();
    }

    private void barAction(String label, Runnable run) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(palette.blue);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.ripple(this, Color.TRANSPARENT, palette.soft, dp(12)));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setOnClickListener(v -> run.run());
        barActions.addView(b, new LinearLayout.LayoutParams(-2, dp(36)));
    }

    // ================= 任务页 =================
    private void pageTasks() {
        barAction("刷新", () -> { if (tasks != null) tasks.refresh(); });
        if (tasks == null) {
            tasks = new TasksPage(this, store, palette, new TasksPage.Host() {
                @Override public void toast(String t) { MainActivity.this.toast(t); }
                @Override public void openSettings() { page = 4; renderPage(); }
            });
        }
        content.addView(tasks.view(), new FrameLayout.LayoutParams(-1, -1));
        if (resumed) tasks.resume();
    }

    // ================= 陪伴页 =================
    private void pageChat() {
        barAction("清空", () -> new android.app.AlertDialog.Builder(this)
                .setTitle("清空聊天记录")
                .setMessage("和鲸宝的聊天记录会全部删除，确定吗？")
                .setPositiveButton("清空", (d, w) -> { WhaleChat.clear(store.prefs); chatSeen = -1; renderPage(); })
                .setNegativeButton("取消", null).show());
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(palette.bg);

        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(16), dp(4), dp(12), dp(6));
        PetService svc = PetService.instance;
        boolean ready = svc != null && svc.bridgeReady();
        boolean enabled = store.prefs.getBoolean("enabled", false);
        TextView dot = new TextView(this);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dot.setTextColor(ready ? 0xff2e8b57 : (enabled ? 0xffcc4444 : palette.muted));
        strip.addView(dot, new LinearLayout.LayoutParams(-2, -2));
        TextView st = new TextView(this);
        st.setText(ready ? "鲸宝在线 · 打字或长按麦克风说话" : (enabled ? "鲸宝未连接 · 去设置检查" : "鲸宝还没起床 · 去设置开启"));
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        st.setTextColor(palette.muted);
        st.setPadding(dp(6), 0, dp(8), 0);
        strip.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
        TextView goSet = new TextView(this);
        goSet.setText("设置");
        goSet.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        goSet.setTextColor(palette.blue);
        goSet.setPadding(dp(10), dp(6), dp(10), dp(6));
        goSet.setOnClickListener(v -> { page = 4; renderPage(); });
        strip.addView(goSet, new LinearLayout.LayoutParams(-2, -2));
        body.addView(strip, new LinearLayout.LayoutParams(-1, -2));

        JSONArray log = WhaleChat.read(store.prefs);
        boolean empty = log.length() == 0 && PetService.streamingText.isEmpty();
        if (empty) {
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setGravity(Gravity.CENTER);
            TextView hi = new TextView(this);
            hi.setText("和" + store.name() + "说说话");
            hi.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            hi.setTextColor(palette.ink);
            hi.setTypeface(Typeface.DEFAULT_BOLD);
            hi.setGravity(Gravity.CENTER);
            mid.addView(hi, new LinearLayout.LayoutParams(-1, -2));
            TextView tip = new TextView(this);
            tip.setText("打字，或长按右下角麦克风说话\n桌面上长按鲸鱼也可以");
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tip.setTextColor(palette.muted);
            tip.setGravity(Gravity.CENTER);
            tip.setPadding(0, dp(10), 0, 0);
            mid.addView(tip, new LinearLayout.LayoutParams(-1, -2));
            body.addView(mid, new LinearLayout.LayoutParams(-1, 0, 1));
        } else {
            chatScroll = new ScrollView(this);
            chatScroll.setFillViewport(false);
            chatScroll.setClipToPadding(false);
            chatList = new LinearLayout(this);
            chatList.setOrientation(LinearLayout.VERTICAL);
            chatList.setPadding(dp(16), dp(8), dp(16), dp(12));
            chatScroll.addView(chatList, new ScrollView.LayoutParams(-1, -2));
            body.addView(chatScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            chatSeen = PetService.chatVersion;
            streamSeen = PetService.streamVersion;
            renderChat();
        }

        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.BOTTOM);
        inputBar.setPadding(dp(12), dp(6), dp(12), dp(8));
        inputBar.setBackgroundColor(palette.bg);
        chatInput = new EditText(this);
        chatInput.setHint("给" + store.name() + "发消息…");
        chatInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        chatInput.setTextColor(palette.ink);
        chatInput.setHintTextColor(palette.muted);
        chatInput.setMaxLines(4);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        chatInput.setBackground(Ui.round(palette.soft, palette.line, dp(20), this));
        chatInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        inputBar.addView(chatInput, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView mic = new ImageView(this);
        mic.setImageResource(iconRes("wave"));
        mic.setColorFilter(palette.blue);
        mic.setPadding(dp(9), dp(9), dp(9), dp(9));
        mic.setBackground(Ui.round(palette.soft, 0, dp(20), this));
        mic.setContentDescription("按住说话");
        mic.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    micHeld = true;
                    PetService s = PetService.instance;
                    if (s == null) toast("先去设置里开启鲸宝");
                    else if (!s.bridgeReady()) toast("鲸宝还没连上");
                    else { s.beginVoice(); toast("在听…松开发送"); }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (micHeld) { micHeld = false; PetService s2 = PetService.instance; if (s2 != null) s2.endVoice(); }
                    return true;
            }
            return false;
        });
        LinearLayout.LayoutParams micLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        micLp.setMargins(dp(8), 0, 0, 0);
        inputBar.addView(mic, micLp);

        TextView send = new TextView(this);
        send.setText("↑");
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        send.setTextColor(Color.WHITE);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setGravity(Gravity.CENTER);
        send.setBackground(Ui.round(palette.blue, 0, dp(20), this));
        send.setContentDescription("发送");
        send.setOnClickListener(v -> sendChat());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(40), dp(40));
        sendLp.setMargins(dp(8), 0, 0, 0);
        inputBar.addView(send, sendLp);
        body.addView(inputBar, new LinearLayout.LayoutParams(-1, -2));
        content.addView(body, new FrameLayout.LayoutParams(-1, -1));
    }

    private void sendChat() {
        if (chatInput == null) return;
        String t = chatInput.getText().toString().trim();
        if (t.isEmpty()) return;
        PetService svc = PetService.instance;
        if (svc == null || !svc.bridgeReady()) { toast("鲸宝还没连上，先去设置"); return; }
        chatInput.setText("");
        svc.sendFromApp(t);
    }

    private void syncChatNow() {
        if (page != 1 || chatList == null) return;
        if (PetService.chatVersion != chatSeen || PetService.streamVersion != streamSeen) {
            chatSeen = PetService.chatVersion;
            streamSeen = PetService.streamVersion;
            renderChat();
        }
    }

    private final Runnable chatTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            syncChatNow();
            handler.postDelayed(this, 700);
        }
    };

    private void renderChat() {
        if (chatList == null) return;
        chatList.removeAllViews();
        JSONArray log = WhaleChat.read(store.prefs);
        for (int i = 0; i < log.length(); i++) {
            JSONObject o = log.optJSONObject(i);
            if (o == null) continue;
            addBubble(o.optString("r").equals("u"), o.optString("t"), o.optLong("at"), false);
        }
        String live = PetService.streamingText;
        if (!live.isEmpty()) addBubble(false, live + " ▍", System.currentTimeMillis(), true);
        chatList.post(() -> {
            ViewParent vp = chatList.getParent();
            if (vp instanceof ScrollView) ((ScrollView) vp).fullScroll(View.FOCUS_DOWN);
        });
    }

    private void addBubble(boolean mine, String text, long at, boolean streaming) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.HORIZONTAL);
        TextView card = new TextView(this);
        card.setText(text);
        card.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        card.setTextIsSelectable(true);
        card.setTextColor(mine ? Color.WHITE : palette.ink);
        card.setBackground(Ui.round(mine ? palette.blue : palette.soft, 0, dp(16), this));
        card.setPadding(dp(12), dp(9), dp(12), dp(9));
        View spacer = new View(this);
        LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 0, 1);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-2, -2);
        if (mine) { side.addView(spacer, spacerLp); side.addView(card, cardLp); }
        else { side.addView(card, cardLp); side.addView(spacer, spacerLp); }
        wrap.addView(side, new LinearLayout.LayoutParams(-1, -2));
        TextView meta = new TextView(this);
        String time = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(at));
        meta.setText(streaming ? store.name() + " 正在说…" : (mine ? "我 · " + time : store.name() + " · " + time));
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        meta.setTextColor(palette.muted);
        meta.setGravity(mine ? Gravity.END : Gravity.START);
        meta.setPadding(dp(4), dp(3), dp(4), 0);
        wrap.addView(meta, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(-1, -2);
        wrapLp.setMargins(0, dp(8), 0, 0);
        chatList.addView(wrap, wrapLp);
    }

    // ================= 互动页 =================
    private void pagePet() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(6), dp(20), 0);
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("今天，一起做什么？");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(palette.ink);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        heading.addView(t, new LinearLayout.LayoutParams(-1, -2));
        TextView n = new TextView(this);
        n.setText("喂食 / 玩耍 / 休息，都会记进足迹。");
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        n.setTextColor(palette.muted);
        n.setPadding(0, dp(6), 0, 0);
        heading.addView(n, new LinearLayout.LayoutParams(-1, -2));
        body.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        PetPanel panel = new PetPanel(this, store, catalog, new PetPanel.Host() {
            @Override public void choose(PetCatalog.Action a, String token) { playAction(a, token); }
            @Override public void mode(String value) { engine.mode(value); }
            @Override public void message(String text) { toast(text); }
            @Override public void confirmation(boolean visible) { heading.setVisibility(visible ? View.VISIBLE : View.GONE); }
        }, false);
        body.addView(panel, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(body, new FrameLayout.LayoutParams(-1, -1));
    }

    private void playAction(PetCatalog.Action a, String token) {
        String refused = store.refusal(a);
        if (refused != null) { toast(refused); return; }
        if (!engine.choose(a, true, token)) return;
        android.app.Dialog dialog = new android.app.Dialog(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(22), dp(22), dp(18));
        card.setBackground(Ui.round(palette.card, palette.line, dp(24), this));
        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        name.setTextColor(palette.ink);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));
        PetAnimationView pet = new PetAnimationView(this);
        card.addView(pet, new LinearLayout.LayoutParams(-1, dp(240)));
        TextView sub = new TextView(this);
        sub.setText(store.name() + "收到你的心意啦");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(palette.muted);
        sub.setPadding(0, dp(14), 0, 0);
        card.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        TextView close = new TextView(this);
        close.setText("收起");
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        close.setTextColor(palette.blue);
        close.setGravity(Gravity.CENTER);
        close.setBackground(Ui.round(palette.soft, 0, dp(14), this));
        close.setPadding(0, dp(12), 0, dp(12));
        close.setOnClickListener(v -> dialog.dismiss());
        card.addView(close, new LinearLayout.LayoutParams(-1, dp(44)));
        dialog.setContentView(card);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setLayout(Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(32)), -2);
        }
        dialog.setOnDismissListener(d -> pet.release());
        dialog.show();
    }

    // ================= 足迹页 =================
    private void pageJournal() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(10), dp(20), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        JSONArray events = store.read().optJSONArray("events");
        if (events == null) events = new JSONArray();
        List<PetJournal.Entry> entries = PetJournal.entries(events, true);
        TextView t = new TextView(this);
        t.setText(events.length() == 0 ? "还没有互动记录" : "最近 " + Math.min(entries.size(), 30) + " 条");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(palette.muted);
        t.setPadding(0, 0, 0, dp(12));
        body.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (entries.isEmpty()) {
            TextView tip = new TextView(this);
            tip.setText("喂一顿饭、玩一会儿，足迹就会开始累积。");
            tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tip.setTextColor(palette.muted);
            body.addView(tip, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        int shown = 0;
        for (PetJournal.Entry e : entries) {
            if (shown++ >= 30) break;
            JSONObject ev = e.event;
            String kind = ev.optString("kind");
            String name = ev.optString("name", ev.optString("mode", ev.optString("actionId", kind)));
            String effect = PetJournal.effects(e.effects);
            String when = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(ev.optLong("at")));
            String line;
            if (kind.equals("feed")) line = "吃了一顿 " + name;
            else if (kind.equals("play")) line = "玩了 " + name;
            else if (kind.equals("mode")) line = "切换到 " + name;
            else line = name;
            if (!effect.isEmpty()) line += "   " + effect;
            if (e.count > 1) line += "  ×" + e.count;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackground(Ui.round(palette.card, palette.line, dp(14), this));
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView l1 = new TextView(this);
            l1.setText(line);
            l1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            l1.setTextColor(palette.ink);
            row.addView(l1, new LinearLayout.LayoutParams(-1, -2));
            TextView l2 = new TextView(this);
            l2.setText(when);
            l2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            l2.setTextColor(palette.muted);
            l2.setPadding(0, dp(3), 0, 0);
            row.addView(l2, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, 0, 0, dp(8));
            body.addView(row, rowLp);
        }
    }

    // ================= 设置页 =================
    private void pageSettings() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(10), dp(20), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout card = section(body);
        cardTitle(card, "连接电脑（DeepSeek）");
        statusLine = new TextView(this);
        statusLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusLine.setTextColor(statusColor());
        statusLine.setText(statusText());
        statusLine.setPadding(dp(2), dp(8), 0, 0);
        urlIn = new EditText(this);
        urlIn.setSingleLine(true);
        urlIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        urlIn.setTextColor(palette.ink);
        urlIn.setHintTextColor(palette.muted);
        urlIn.setHint("ws://192.168.x.x:28000/whale-pet/ws");
        urlIn.setText(store.prefs.getString("bridge-url", ""));
        urlIn.setBackground(Ui.round(palette.soft, palette.line, dp(14), this));
        urlIn.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams urlLp = new LinearLayout.LayoutParams(-1, -2);
        urlLp.setMargins(0, dp(12), 0, 0);
        card.addView(urlIn, urlLp);
        tokenIn = new EditText(this);
        tokenIn.setSingleLine(true);
        tokenIn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tokenIn.setTextColor(palette.ink);
        tokenIn.setHintTextColor(palette.muted);
        tokenIn.setHint("配对码");
        String tk = store.prefs.getString("bridge-token", "");
        tokenIn.setText(tk.isEmpty() ? "whale-pair-2026" : tk);
        tokenIn.setBackground(Ui.round(palette.soft, palette.line, dp(14), this));
        tokenIn.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams tkLp = new LinearLayout.LayoutParams(-1, -2);
        tkLp.setMargins(0, dp(10), 0, 0);
        card.addView(tokenIn, tkLp);

        LinearLayout row = new LinearLayout(this);
        TextView test = new TextView(this);
        test.setText("测试连接");
        test.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        test.setTextColor(palette.blue);
        test.setGravity(Gravity.CENTER);
        test.setBackground(Ui.round(palette.soft, palette.line, dp(14), this));
        test.setOnClickListener(v -> {
            String u = urlIn.getText().toString().trim();
            if (u.isEmpty()) { toast("先填服务器地址"); return; }
            saveConn();
            statusLine.setText("连接中…");
            statusLine.setTextColor(palette.muted);
            PetService.testBridgeStatic(this, u, tokenIn.getText().toString().trim(), msg -> handler.post(() -> {
                if (msg == null) { statusLine.setText("✓ 连接成功"); statusLine.setTextColor(0xff2e8b57); }
                else { statusLine.setText("✗ " + msg); statusLine.setTextColor(0xffcc4444); }
            }));
        });
        row.addView(test, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView save = new TextView(this);
        save.setText("保存并连接");
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        save.setTextColor(Color.WHITE);
        save.setGravity(Gravity.CENTER);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setBackground(Ui.round(palette.blue, 0, dp(14), this));
        save.setOnClickListener(v -> {
            saveConn();
            PetService svc = PetService.instance;
            if (svc != null) { svc.reconnect(); toast("已保存，正在连接…"); }
            else toast("已保存，开启桌宠后生效");
            statusLine.setText(statusText());
            statusLine.setTextColor(statusColor());
            if (tasks != null) tasks.refresh();
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, dp(44), 1);
        saveLp.setMargins(dp(8), 0, 0, 0);
        row.addView(save, saveLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, dp(12), 0, 0);
        card.addView(row, rowLp);
        card.addView(statusLine, new LinearLayout.LayoutParams(-1, -2));

        TextView guide = new TextView(this);
        guide.setText("连接方式：\n① 同一 WiFi：ws://电脑IP:28000/whale-pet/ws\n② 外网：自己的 Cloudflare 隧道 → wss://域名/whale-pet/ws");
        guide.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        guide.setTextColor(palette.muted);
        guide.setLineSpacing(dp(3), 1);
        guide.setPadding(0, dp(12), 0, 0);
        card.addView(guide, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout pet = section(body);
        cardTitle(pet, "桌面小宠物");
        Switch enable = new Switch(this);
        enable.setText("让她到桌面陪我");
        enable.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        enable.setTextColor(palette.ink);
        enable.setChecked(store.prefs.getBoolean("enabled", false));
        enable.setOnCheckedChangeListener((b, v) -> { if (v) startPet(); else stopPet(); });
        pet.addView(enable, new LinearLayout.LayoutParams(-1, -2));
        TextView overlayTip = new TextView(this);
        overlayTip.setText("首次开启会请求「显示在其他应用上层」权限。");
        overlayTip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        overlayTip.setTextColor(palette.muted);
        overlayTip.setPadding(0, dp(8), 0, 0);
        pet.addView(overlayTip, new LinearLayout.LayoutParams(-1, -2));

        TextView sizeLabel = new TextView(this);
        int curSize = store.prefs.getInt("size", 168);
        sizeLabel.setText("宠物大小 · " + curSize);
        sizeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sizeLabel.setTextColor(palette.ink);
        sizeLabel.setPadding(0, dp(14), 0, dp(6));
        pet.addView(sizeLabel, new LinearLayout.LayoutParams(-1, -2));
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(120); // 108 ~ 228
        seek.setProgress(curSize - 108);
        seek.getProgressDrawable().setColorFilter(palette.blue, android.graphics.PorterDuff.Mode.SRC_IN);
        try { seek.getThumb().setColorFilter(palette.blue, android.graphics.PorterDuff.Mode.SRC_IN); } catch (Exception ignored) { }
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int v, boolean fromUser) {
                int size = v + 108;
                sizeLabel.setText("宠物大小 · " + size);
                if (fromUser) {
                    store.prefs.edit().putInt("size", size).apply();
                    PetService svc = PetService.instance;
                    if (svc != null) svc.applySize(size);
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
        pet.addView(seek, new LinearLayout.LayoutParams(-1, -2));
        TextView sizeHint = new TextView(this);
        sizeHint.setText("拖动调整桌面鲸鱼的大小，立即生效。");
        sizeHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sizeHint.setTextColor(palette.muted);
        sizeHint.setPadding(0, dp(4), 0, 0);
        pet.addView(sizeHint, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ui = section(body);
        cardTitle(ui, "显示");
        Switch dark = new Switch(this);
        dark.setText("深色模式");
        dark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        dark.setTextColor(palette.ink);
        dark.setChecked(store.dark());
        dark.setOnCheckedChangeListener((b, v) -> {
            store.prefs.edit().putBoolean("dark", v).apply();
            palette = new Ui.Palette(v);
            buildShell();
            renderPage();
            applyBarTint();
        });
        ui.addView(dark, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout about = section(body);
        cardTitle(about, "关于偶鲸鲸");
        TextView ver = new TextView(this);
        ver.setText("偶鲸鲸 · 口袋里的海 × DeepSeek\n原生客户端（无网页）\n基于开源项目 泡泡鲸（whale-pet）改造");
        ver.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        ver.setTextColor(palette.muted);
        ver.setLineSpacing(dp(3), 1);
        about.addView(ver, new LinearLayout.LayoutParams(-1, -2));
        TextView link = new TextView(this);
        link.setText("GitHub 项目地址 >");
        link.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        link.setTextColor(palette.blue);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setPadding(0, dp(10), 0, 0);
        link.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xuanduo-077/dsh-pet-on-phone"))));
        about.addView(link, new LinearLayout.LayoutParams(-1, -2));
    }

    private void saveConn() {
        store.prefs.edit()
                .putString("bridge-url", urlIn.getText().toString().trim())
                .putString("bridge-token", tokenIn.getText().toString().trim())
                .apply();
    }

    private String statusText() {
        boolean on = store.prefs.getBoolean("enabled", false);
        PetService svc = PetService.instance;
        boolean ready = svc != null && svc.bridgeReady();
        if (!on) return "桌宠未开启 · 开启后自动连接";
        return ready ? "已连接 · 鲸宝在线" : "桌宠已开启 · 正在连接…";
    }

    private int statusColor() {
        boolean on = store.prefs.getBoolean("enabled", false);
        PetService svc = PetService.instance;
        boolean ready = svc != null && svc.bridgeReady();
        return ready ? 0xff2e8b57 : (on ? 0xffcc4444 : palette.muted);
    }

    private LinearLayout section(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.round(palette.card, palette.line, dp(18), this));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 0, 0, dp(14));
        parent.addView(card, lp);
        return card;
    }

    private void cardTitle(LinearLayout card, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTextColor(palette.ink);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    // ================= 桌宠开关 =================
    private void startPet() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true;
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
        store.prefs.edit().putBoolean("enabled", true).apply();
        try {
            startForegroundService(new Intent(this, PetService.class));
        } catch (Exception e) {
            store.prefs.edit().putBoolean("enabled", false).apply();
            toast("暂时无法开启桌宠，请重试");
        }
    }

    private void stopPet() {
        store.prefs.edit().putBoolean("enabled", false).apply();
        stopService(new Intent(this, PetService.class));
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    // ================= PetEngine.Host =================
    @Override public void show(String id, Runnable loaded) { if (loaded != null) loaded.run(); }
    @Override public void active(boolean value) { }
    @Override public void message(String text) { toast(text); }
    @Override public void changed() { }
    @Override public boolean move(boolean right, int duration) { return false; }
    @Override public void stopMotion() { }

    // ================= 状态轮询 =================
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (page == 4 && statusLine != null) { statusLine.setText(statusText()); statusLine.setTextColor(statusColor()); }
            handler.postDelayed(this, 3000);
        }
    };
}
