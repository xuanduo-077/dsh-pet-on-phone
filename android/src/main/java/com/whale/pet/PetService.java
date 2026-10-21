package com.whale.pet;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Native small-window service, following baifanyu's retained-View/screen lifecycle. */
public final class PetService extends Service implements PetEngine.Host,PetAnimationView.Gesture {
    static final String STATUS_CHANNEL="companion_status_v2";
    public static volatile PetService instance;
    private PetStore store;private PetCatalog catalog;private PetEngine engine;
    private WindowManager wm;private WindowManager.LayoutParams lp,menuLp;
    private android.widget.FrameLayout root;private PetAnimationView pet;private Ui.Bubble bubble;private View menu;
    private final Handler handler=new Handler(Looper.getMainLooper());private ValueAnimator motion;
    private boolean registered,screenActive,dragging,bubbleBelow;private float worldX,worldY;private boolean positionReady;private PetCatalog.Clip shownClip;private String bubbleText="";private int motionTarget;
    private final String session=UUID.randomUUID().toString();private long taps,holds,sleeps;
    private WhaleBridge bridge;private boolean streamBubble;private final StringBuilder streamBuf=new StringBuilder();
    /** 供 App 界面显示鲸宝连接状态。 */
    public boolean bridgeReady(){return bridge!=null&&bridge.isReady();}
    /** 聊天记录版本号：每次写入 +1，聊天页轮询刷新。 */
    public static volatile int chatVersion=0;
    /** 给 App 聊天页读的实时流式文本（onDelta 累积，onDone/onError 清空）。 */
    public static volatile String streamingText="";
    public static volatile int streamVersion=0;
    private void logChat(String role,String text){WhaleChat.append(store.prefs,role,text);chatVersion++;}
    private View settings;private WindowManager.LayoutParams settingsLp;
    private PetVoice voice;private boolean voiceListening;
    private final Runnable careTick=new Runnable(){@Override public void run(){if(screenActive){reminders();snapshot();handler.postDelayed(this,5000);}}};
    private final Runnable clearBubble=()->{bubbleText="";layoutPet(false);};
    private final BroadcastReceiver screen=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent intent){
        if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){sleeps++;handler.removeCallbacks(careTick);hideMenu();pauseForScreen();}
        else {updateVisibility();for(long delay:new long[]{120,450,1000,2500})handler.postDelayed(()->{updateVisibility();snapshot();},delay);}
        snapshot();
    }};
    private int dp(float n){return Ui.dp(this,n);}
    private int width(){return getResources().getDisplayMetrics().widthPixels;}
    private int height(){return getResources().getDisplayMetrics().heightPixels;}
    private int petSize(){return dp(store.prefs.getInt("size",168));}
    private void savePosition(){if(positionReady&&lp!=null)store.prefs.edit().putFloat("anchor-x",worldX/Math.max(1,width())).putFloat("anchor-y",worldY/Math.max(1,height())).apply();}
    private void restorePosition(){if(!positionReady){int size=petSize();worldX=store.prefs.contains("anchor-x")?store.prefs.getFloat("anchor-x",.8f)*width():store.prefs.getFloat("x",.8f)*(width()-size)+size/2f;worldY=store.prefs.contains("anchor-y")?store.prefs.getFloat("anchor-y",.8f)*height():store.prefs.getFloat("y",.72f)*(height()-size-dp(30))+size;positionReady=true;}worldX=Math.max(petSize()*.4f,Math.min(width()-petSize()*.4f,worldX));worldY=Math.max(petSize()+dp(28),Math.min(height()-dp(30),worldY));}
    @Override public void onCreate(){
        super.onCreate();instance=this;store=new PetStore(this);catalog=new PetCatalog(this);wm=getSystemService(WindowManager.class);engine=new PetEngine(store,catalog,this);
        // A new silent channel also migrates installs whose old channel could alert.
        NotificationChannel status=new NotificationChannel(STATUS_CHANNEL,"陪伴状态",NotificationManager.IMPORTANCE_LOW);status.setDescription("静默显示桌面陪伴状态");status.setSound(null,null);status.enableVibration(false);status.enableLights(false);status.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(status);
        try{if(Build.VERSION.SDK_INT>=34)startForeground(7,notification(),android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(7,notification());}catch(Exception e){android.util.Log.e("WhaleNative","Foreground service failed",e);stopSelf();return;}
        if(!Settings.canDrawOverlays(this)||!store.prefs.getBoolean("enabled",false)){stopSelf();return;}
        createPet();layoutPet(false);connectBridge();IntentFilter f=new IntentFilter();f.addAction(Intent.ACTION_SCREEN_OFF);f.addAction(Intent.ACTION_SCREEN_ON);f.addAction(Intent.ACTION_USER_PRESENT);registerReceiver(screen,f);registered=true;updateVisibility();
    }
    /** 连接 DSH 桥（本地 NAS whale-bridge 插件）。地址与配对码存 SharedPreferences，可随时在设置里改。 */
    private void connectBridge(){
        if(voice!=null){voice.destroy();voice=null;}
        if(bridge!=null){bridge.close();bridge=null;}
                String url=store.prefs.getString("bridge-url","");
        String token=store.prefs.getString("bridge-token","whale-pair-2026");
        // 未配置过地址：先弹连接向导（不再用旧默认地址，那只是开发者家的 NAS）。
        if(url.isEmpty()){showSettings(true);return;}
        bridge=new WhaleBridge(url,token,new WhaleBridge.Listener(){
            @Override public void onReady(String model){message("鲸宝上线啦，长按我说话");updateNotification();}
            @Override public void onDelta(String delta){if(!streamBubble){engine.playClip("happy");}streamBuf.append(delta);streamingText=streamBuf.toString();streamVersion++;streamMessage(streamBuf.toString());}
            @Override public void onDone(String fullText){streamBubble=false;streamBuf.setLength(0);streamingText="";streamVersion++;logChat("w",fullText);message(fullText);}
            @Override public void onError(String m){streamBubble=false;streamBuf.setLength(0);streamingText="";streamVersion++;message("…"+m);if(m!=null&&m.contains("配对码"))updateNotification();}
            @Override public void onDisconnected(){if(streamBubble){streamBubble=false;streamBuf.setLength(0);streamingText="";streamVersion++;message("鲸宝掉线了，正在重连…");}updateNotification();}
            @Override public void onDisconnectReason(String reason){android.util.Log.w("WhaleBridge","main reason: "+reason);}
        });
        bridge.connect();
        voice=new PetVoice(this);
            }
    /** 长按开始：进入聆听 */
    private void startListening(){
        if(voice==null||!screenActive)return;
        if(voiceListening)return;
        if(!store.prefs.getBoolean("chat-enabled",true))return;
        if(bridge==null||!bridge.isReady()){message("鲸宝还没连上，等一下再试");return;}
        voiceListening=true;
        handler.removeCallbacks(clearBubble); // 聆听期间气泡别被 7s 清掉
        engine.playClip("greet");
        message("我在听…（松开发送）");
        voice.start(new PetVoice.Listener(){
            @Override public void onVolume(float v){
                if(!voiceListening)return;
                // 音量越大鲸鱼越开心：换算成字符数量的「♪」，形成声波跳动效果
                int bars=1+Math.round(v*5f);
                StringBuilder sb=new StringBuilder("我在听 ");
                for(int i=0;i<bars;i++)sb.append("♪");
                bubbleText=sb.toString();bubble.text(bubbleText);bubble.setVisibility(View.VISIBLE);
            }
            @Override public void onPartial(String text){if(!text.isEmpty())streamMessage("「"+text+"」…");}
            @Override public void onFinal(String text){
                voiceListening=false;
                if(text==null||text.trim().isEmpty()){message("没听清，再长按说一次吧");return;}
                logChat("u",text.trim());
                engine.playClip("happy");
                thinking("「"+text.trim()+"」");
                sendToWhale(text.trim());
            }
            @Override public void onError(String m){
                // 松手后系统识别器常发一个迟到的过期错误（busy/no_match），listening 已结束就忽略
                if(!voiceListening){android.util.Log.d("WhaleVoice","ignore late voice error: "+m);return;}
                voiceListening=false;
                // 权限类错误静默：识别器起不来多半是系统瞬时限麦（仅使用时允许/后台限制），不弹气泡吓用户
                if(m==null||m.isEmpty()){message("（好像没听到我说话…再长按试试？）");return;}
                message(m);
            }
        });
    }
    private void endListening(){ if(voiceListening&&voice!=null) voice.stop(); }
    /** 思考中占位：气泡显示问题+省略号动画，回包后由 onDelta/onDone 覆盖 */
    private void thinking(String question){
        streamBubble=true;bubbleText=question+" …";bubble.text(bubbleText);bubble.setVisibility(View.VISIBLE);
        handler.removeCallbacks(clearBubble);
    }
    /** 发一句话给鲸宝（语音/打字共用）。fromApp=true 表示来自聊天页，不重复记（聊天页已记）。 */
    private void sendToWhale(String text){sendToWhale(text,false);}
    private void sendToWhale(String text,boolean fromApp){
        logChat("u",text);
        if(bridge==null||!bridge.isReady()){message("鲸宝还没连上，等一下再试");return;}
        if(!streamBubble){message("…");}bridge.sendText(text);
    }
    /** 聊天页发送入口：App 打开时 PetService 常驻，直接走这里（气泡照样显示）。 */
    public void sendFromApp(String text){sendToWhale(text,true);}
    /** App 聊天页按住说话：按下开始、松开发送。 */
    /** 设置里改完地址/配对码后立刻重连（不用杀进程）。 */
    public void reconnect(){connectBridge();updateNotification();}
    public void beginVoice(){if(!bridgeReady()){message("鲸宝还没连上，先去设置里配地址");return;}startListening();}
    public void endVoice(){endListening();}
    public boolean voiceBusy(){return voiceListening;}
    /** 聊天输入行：放进长按菜单，避免额外 overlay 窗口。 */
    private void addChatRow(LinearLayout box,Ui.Palette p){
        LinearLayout chat=Ui.row(this);chat.setTag("chat-input");EditText input=new EditText(this);input.setHint("跟鲸宝说点什么…");input.setTextSize(14);input.setMaxLines(1);input.setBackground(null);input.setTextColor(p.ink);input.setHintTextColor(0x88000000);
        // 点输入框时让窗口可聚焦以弹输入法；点别处/关菜单恢复。
        input.setOnFocusChangeListener((v,has)->makeMenuFocusable(has));
        input.setOnClickListener(v->makeMenuFocusable(true));
        chat.addView(input,new LinearLayout.LayoutParams(0,-2,1));
        TextView send=Ui.text(this,"发送",14,p.ink,true);send.setPadding(dp(10),dp(6),dp(4),dp(6));send.setOnClickListener(v->{String t=input.getText().toString().trim();if(t.isEmpty())return;input.setText("");sendToWhale(t);});
        chat.addView(send,-2,-2);Ui.add(box,chat,-2,4);
    }
    private void createPet(){
        // As in baifanyu: a compact NOT_FOCUSABLE native window, retained through sleep.
        pet=new PetAnimationView(this);pet.gesture(this);pet.setContentDescription("桌面宠物");bubble=new Ui.Bubble(this,new Ui.Palette(store.dark()));
        root=new FrameLayout(this){@Override public boolean dispatchTouchEvent(MotionEvent e){return pet.dispatchTouchEvent(e);}};
        root.addView(pet,new FrameLayout.LayoutParams(-1,petSize(),Gravity.BOTTOM));root.addView(bubble,new FrameLayout.LayoutParams(-1,dp(52),Gravity.TOP));bubble.setVisibility(View.GONE);
        lp=new WindowManager.LayoutParams(petSize(),petSize(),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;lp.setTitle("泡泡鲸·桌面宠物");restorePosition();
        try{wm.addView(root,lp);}catch(Exception e){android.util.Log.e("WhaleNative","Cannot attach overlay",e);pet.release();pet=null;root=null;store.prefs.edit().putBoolean("enabled",false).apply();stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"STOP".equals(intent.getAction())){stopPet();return START_NOT_STICKY;}
        if(!store.prefs.getBoolean("enabled",false)){stopSelf();return START_NOT_STICKY;}
        if(intent!=null&&"RECOVER".equals(intent.getAction())&&pet!=null){pet.screenActive(false);pet.screenActive(true);root.requestLayout();root.postInvalidateOnAnimation();}
        if(intent!=null&&"CHAT".equals(intent.getAction())){handler.postDelayed(this::openChat,150);}
        if(intent!=null&&"SETTINGS".equals(intent.getAction())){handler.postDelayed(()->showSettings(false),150);}
        updateVisibility();return START_STICKY;
    }
    public void updateVisibility(){
        if(root==null)return;if(!Settings.canDrawOverlays(this)){stopPet();return;}
        boolean interactive=getSystemService(PowerManager.class).isInteractive(),locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
        // Do not set INVISIBLE, remove, or recreate the pet when the screen locks.
        // System window visibility and explicit render pause are separate concerns.
        root.setVisibility(MainActivity.visible?View.GONE:View.VISIBLE);
        boolean run=interactive&&!locked&&!MainActivity.visible;
        if(run&&!screenActive){screenActive=true;engine.resume();root.requestLayout();root.postInvalidateOnAnimation();handler.removeCallbacks(careTick);handler.postDelayed(careTick,500);}
        else if(!run)pauseForScreen();
        else if(run){pet.screenActive(true);root.postInvalidateOnAnimation();}
    }
    private void pauseForScreen(){screenActive=false;if(engine!=null)engine.pause();stopMotion();handler.removeCallbacks(careTick);handler.removeCallbacks(clearBubble);if(!bubbleText.isEmpty()){bubbleText="";layoutPet(false);}snapshot();}
    public void applySettings(){if(pet==null)return;bubble.palette(new Ui.Palette(store.dark()));stopMotion();savePosition();layoutPet(false);getSystemService(NotificationManager.class).notify(7,notification());changed();}
    /** 估算气泡高度：11sp 字、每行 16dp，基础 52dp，最多 6 行封顶 150dp。 */
    private int bubbleHeight(String text,int boxW){
        android.graphics.Paint pm=new android.graphics.Paint();pm.setTextSize(11*getResources().getDisplayMetrics().density);
        float max=boxW-26*getResources().getDisplayMetrics().density;int lines=1;String rest=text==null?"":text;
        while(!rest.isEmpty()){int n=pm.breakText(rest,true,max,null);if(n<=0||n>=rest.length())break;rest=rest.substring(n);lines++;}
        return Math.min(dp(52)+(Math.min(lines,6)-1)*dp(16),dp(150));
    }
    private void layoutPet(boolean persist){
        if(root==null||lp==null)return;if(persist)savePosition();restorePosition();
        PetCatalog.Clip clip=shownClip==null?catalog.clips.get("idle"):shownClip;
        RectF envelope=PetAnimationView.envelope(clip,petSize());int speechHeight=bubbleText.isEmpty()?0:bubbleHeight(bubbleText,Math.min(dp(220),lp.width));
        // Intersect the complete action envelope with the display; body anchor does not change.
        int left=Math.max(0,(int)Math.floor(worldX+envelope.left)-dp(4));
        int right=Math.min(width(),(int)Math.ceil(worldX+envelope.right)+dp(4));
        if(speechHeight>0){left=Math.min(left,Math.max(0,(int)worldX-dp(110)));right=Math.max(right,Math.min(width(),(int)worldX+dp(110)));}
        int top=Math.max(dp(28),(int)Math.floor(worldY+envelope.top)-dp(4));
        int bottom=Math.min(height()-dp(24),(int)Math.ceil(worldY+envelope.bottom)+dp(4));
        bubbleBelow=top-speechHeight<dp(28);int imageTop=top;
        if(speechHeight>0){if(bubbleBelow)bottom=Math.min(height()-dp(24),bottom+speechHeight);else top-=speechHeight;}
        lp.x=left;lp.y=top;lp.width=Math.max(dp(80),right-left);lp.height=Math.max(dp(80),bottom-top);
        FrameLayout.LayoutParams image=new FrameLayout.LayoutParams(-1,-1);pet.setLayoutParams(image);pet.bodySize(petSize());pet.anchor(worldX-left,worldY-top);
        int bw=Math.min(dp(220),lp.width);FrameLayout.LayoutParams speech=new FrameLayout.LayoutParams(bw,bubbleHeight(bubbleText,bw),(bubbleBelow?Gravity.BOTTOM:Gravity.TOP)|Gravity.LEFT);speech.leftMargin=Math.max(0,Math.min(lp.width-bw,Math.round(worldX-left-bw/2f)));bubble.placement(bubbleBelow,worldX-left-speech.leftMargin);bubble.setLayoutParams(speech);bubble.setVisibility(bubbleText.isEmpty()?View.GONE:View.VISIBLE);bubble.text(bubbleText);
        try{wm.updateViewLayout(root,lp);}catch(IllegalArgumentException ignored){}
    }
    private void reminders(){if(menu==null&&!dragging)engine.chatTick();}
    @Override public void message(String text){if(!screenActive)return;engine.noteSpeech(text);hideMenu();savePosition();bubbleText=text;handler.removeCallbacks(clearBubble);layoutPet(false);handler.postDelayed(clearBubble,7000);snapshot();}
    /** 流式回复专用：只更新气泡文本，不动引擎/布局节流由 layoutPet 自身控制，不走 7s 自动清（由 onDone 重置）。 */
    private void streamMessage(String text){if(!screenActive||bubble==null)return;streamBubble=true;bubbleText=text;bubble.text(text);bubble.setVisibility(View.VISIBLE);}
    @Override public void show(String id,Runnable loaded){if(pet!=null){pet.mirror(false);pet.show(catalog.clips.get(id),()->{shownClip=catalog.clips.get(id);layoutPet(false);loaded.run();});}else loaded.run();}
    @Override public void active(boolean value){if(pet!=null)pet.screenActive(value);}
    @Override public void changed(){snapshot();}
    @Override public boolean move(boolean right,int duration){
        if(pet==null||menu!=null||dragging||!screenActive||!store.prefs.getBoolean("roam",true))return false;stopMotion();int start=Math.round(worldX),min=Math.round(-PetAnimationView.envelope(shownClip==null?catalog.clips.get("idle"):shownClip,petSize()).left),max=width()-min,end=right?max:min;if(Math.abs(end-start)<dp(10))end=end==min?max:min;pet.faceToward(end>start);motionTarget=end;
        ValueAnimator animator=ValueAnimator.ofInt(start,end);motion=animator;animator.setDuration(duration);animator.setInterpolator(new android.view.animation.LinearInterpolator());animator.addUpdateListener(a->{worldX=(int)a.getAnimatedValue();layoutPet(false);});animator.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator a){savePosition();if(motion==a)motion=null;}});animator.start();return true;
    }
    @Override public void stopMotion(){if(motion!=null){ValueAnimator old=motion;motion=null;old.cancel();}if(store!=null)savePosition();}
    @Override public void down(){stopMotion();}
    @Override public void dragStart(){dragging=true;handler.removeCallbacks(clearBubble);savePosition();bubbleText="";layoutPet(false);engine.drag();}
    @Override public void drag(float dx,float dy){worldX+=dx;worldY+=dy;layoutPet(false);}
    @Override public void drop(){dragging=false;savePosition();engine.drop();engine.sayEvent("drop");snapshot();}
    @Override public void tap(){taps++;engine.touch();snapshot();}
    @Override public void hold(){holds++;startListening();snapshot();}
    @Override public void holdEnd(){endListening();}
    private void showMenu(){
        if(!screenActive||menu!=null)return;engine.menu(true);Context c=this;Ui.Palette p=new Ui.Palette(store.dark());LinearLayout box=Ui.column(c);box.setPadding(dp(5),dp(10),dp(5),dp(5));box.setBackground(Ui.round(p.bg,p.line,24,c));
        LinearLayout heading=Ui.row(c);heading.setTag("menu-heading");TextView title=Ui.text(c,"陪伴时光",16,p.ink,true);title.setPadding(dp(15),0,dp(8),0);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));heading.addView(Ui.iconButton(c,"close","关闭菜单",p,this::dismissMenu),new LinearLayout.LayoutParams(dp(44),dp(44)));Ui.add(box,heading,-2,4);
        PetPanel panel=new PetPanel(c,store,catalog,new PetPanel.Host(){
            @Override public void choose(PetCatalog.Action a,String token){hideMenu();engine.choose(a,true,token);}
            @Override public void mode(String mode){engine.mode(mode);}
            @Override public void message(String message){PetService.this.message(message);}
            @Override public void confirmation(boolean visible){resizeMenu(visible);}
        },true);panel.setTag("actions-panel");box.addView(panel,new LinearLayout.LayoutParams(-1,0,1));
        addChatRow(box,p);
        LinearLayout footer=Ui.row(this);footer.setTag("menu-footer");Ui.weight(footer,Ui.labeled(c,"回到小屋","home",p,PetService.this::openApp),3);Ui.weight(footer,Ui.labeled(c,"待会儿见","moon",p,PetService.this::stopPet),3);Ui.add(box,footer,-2,4);
        menuLp=new WindowManager.LayoutParams(Math.min(dp(352),width()-dp(24)),Math.min(dp(570),height()-dp(100)),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);menuLp.gravity=Gravity.CENTER;menuLp.setTitle("泡泡鲸·互动菜单");menu=box;
        try{wm.addView(menu,menuLp);Ui.enter(menu);}catch(Exception e){menu=null;engine.menu(false);}
    }
    private void resizeMenu(boolean confirmation){
        if(menu==null||menuLp==null)return;View panel=menu.findViewWithTag("actions-panel");if(panel==null)return;
        for(String tag:new String[]{"menu-heading","menu-footer"}){View chrome=menu.findViewWithTag(tag);if(chrome!=null)chrome.setVisibility(confirmation?View.GONE:View.VISIBLE);}
        panel.setLayoutParams(new LinearLayout.LayoutParams(-1,confirmation?-2:0,confirmation?0:1));
        menuLp.height=confirmation?WindowManager.LayoutParams.WRAP_CONTENT:Math.min(dp(570),height()-dp(100));
        try{wm.updateViewLayout(menu,menuLp);}catch(IllegalArgumentException ignored){}
    }
    /** 输入法修复：菜单窗平时 NOT_FOCUSABLE，点输入框时临时摘掉该 flag 让 IME 弹出。 */
    private void makeMenuFocusable(boolean focusable){
        if(menu==null||menuLp==null)return;
        int want=focusable?0:(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        int keep=~(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        int updated=(menuLp.flags&keep)|want;
        if(updated==menuLp.flags)return;
        menuLp.flags=updated;
        if(focusable)menuLp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        try{wm.updateViewLayout(menu,menuLp);}catch(IllegalArgumentException ignored){}
    }
    /** 外部入口：气泡里的「打字」入口打开菜单并聚焦输入框。 */
    public void openChat(){showMenu();makeMenuFocusable(true);View panel=menu==null?null:menu.findViewWithTag("chat-input");if(panel!=null)panel.requestFocus();}
    // ---------------- 连接设置（v4） ----------------
    /** 连接设置面板：独立 overlay，可聚焦（输入框需要输入法）。first=true 为首次向导，无关闭按钮。 */
    void showSettings(boolean first){
        if(settings!=null)hideSettings();
        if(!screenActive&&!first)return;
        Context c=this;Ui.Palette p=new Ui.Palette(store.dark());LinearLayout box=Ui.column(c);
        box.setPadding(dp(16),dp(14),dp(16),dp(12));box.setBackground(Ui.round(p.bg,p.line,24,c));box.setClickable(true);
        TextView title=Ui.text(c,first?"连接鲸宝的家":"连接设置",16,p.ink,true);title.setPadding(0,0,0,dp(4));Ui.add(box,title,-2,2);
        TextView hint=Ui.text(c,first?"第一次见面！先告诉鲸宝它的 DeepSeek 家在哪里。\n①电脑/NAS 装好 dsh-whale-bridge 插件\n②填服务器地址和配对码，点测试连接":"修改后点「保存并重连」生效",12,p.muted,false);hint.setPadding(0,0,0,dp(8));Ui.add(box,hint,-2,2);
        TextView urlLabel=Ui.text(c,"服务器地址",12,p.ink,true);Ui.add(box,urlLabel,-2,2);
        EditText url=new EditText(c);url.setTag("set-url");url.setSingleLine(true);url.setTextSize(13);url.setTextColor(p.ink);url.setHintTextColor(0x66000000);
        url.setHint("如 ws://192.168.1.10:28000/whale-pet/ws 或 wss://xxx.trycloudflare.com/whale-pet/ws");
        url.setText(store.prefs.getString("bridge-url",""));url.setBackground(Ui.round(p.card,p.line,10,c));url.setPadding(dp(10),dp(9),dp(10),dp(9));
        LinearLayout.LayoutParams urlLp=new LinearLayout.LayoutParams(-1,-2);urlLp.bottomMargin=dp(8);box.addView(url,urlLp);
        TextView tokenLabel=Ui.text(c,"配对码（与插件 config.token 一致）",12,p.ink,true);Ui.add(box,tokenLabel,-2,2);
        EditText token=new EditText(c);token.setTag("set-token");token.setSingleLine(true);token.setTextSize(13);token.setTextColor(p.ink);token.setHintTextColor(0x66000000);
        token.setHint("默认 whale-pair-2026");token.setText(store.prefs.getString("bridge-token","whale-pair-2026"));token.setBackground(Ui.round(p.card,p.line,10,c));token.setPadding(dp(10),dp(9),dp(10),dp(9));
        LinearLayout.LayoutParams tokenLp=new LinearLayout.LayoutParams(-1,-2);tokenLp.bottomMargin=dp(10);box.addView(token,tokenLp);
        TextView status=Ui.text(c,"",12,p.muted,false);status.setTag("set-status");status.setPadding(0,0,0,dp(8));Ui.add(box,status,-2,2);
        LinearLayout row=new LinearLayout(c);row.setOrientation(LinearLayout.HORIZONTAL);
        TextView test=Ui.button(c,"测试连接",p,()->{
            String tk=token.getText().toString().trim();
            String u=normalizeWsUrl(url.getText().toString().trim());
            if(u==null){status.setText("请先填服务器地址（IP、域名或完整 ws/wss 地址均可）");status.setTextColor(0xffcc4444);return;}
            url.setText(u);
            status.setText("连接中…");status.setTextColor(p.muted);
            testBridge(u,tk,msg->handler.post(()->{
                if(msg==null){status.setText("✓ 连接成功，鲸宝就位！");status.setTextColor(0xff2e8b57);
                    store.prefs.edit().putString("bridge-url",u).putString("bridge-token",tk).apply();
                    if(first)handler.postDelayed(this::hideSettings,900);else handler.postDelayed(this::hideSettings,900);
                    handler.postDelayed(this::connectBridge,950);
                }else{status.setText("✗ "+msg);status.setTextColor(0xffcc4444);}
            }));
        });
        TextView save=Ui.button(c,first?"跳过，稍后再连":"保存并重连",p,()->{
            if(first){hideSettings();message("在通知栏点「连接设置」随时配置");return;}
            store.prefs.edit().putString("bridge-url",url.getText().toString().trim()).putString("bridge-token",token.getText().toString().trim()).apply();
            hideSettings();message("正在重连鲸宝…");handler.postDelayed(this::connectBridge,600);
        });
        Ui.weight(row,test,1);Ui.weight(row,save,1);Ui.add(box,row,dp(44),2);
        settings=box;
        settingsLp=new WindowManager.LayoutParams(Math.min(dp(360),width()-dp(24)),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,first?0:(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),PixelFormat.TRANSLUCENT);
        settingsLp.gravity=Gravity.CENTER;settingsLp.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;settingsLp.setTitle("泡泡鲸·连接设置");
        try{wm.addView(settings,settingsLp);Ui.enter(settings);}catch(Exception e){settings=null;}
    }
    void hideSettings(){if(settings!=null){try{wm.removeView(settings);}catch(IllegalArgumentException ignored){}settings=null;}}
    /** 测试连接：临时建一条 WS 握手，成功回调 null，失败回调错误文案。 */
    /** 用户输入容错：纯 IP / host:port / 域名 自动补成完整 ws 地址；已带 ws:// wss:// http(s):// 的自动转换。 */
    static String normalizeWsUrl(String raw){
        if(raw==null)return null;raw=raw.trim();if(raw.isEmpty())return null;
        if(raw.startsWith("ws://")||raw.startsWith("wss://")){return raw.endsWith("/")?raw:raw+"/";}
        if(raw.startsWith("http://"))return "ws://"+raw.substring(7)+(raw.endsWith("/")?"":"/")+"";
        if(raw.startsWith("https://"))return "wss://"+raw.substring(8)+(raw.endsWith("/")?"":"/")+"";
        String path=raw.matches(".*:\\d+.*")?"/whale-pet/ws":"/whale-pet/ws"; // 统一路径
        // 无路径输入（如 192.168.1.10 或 mynas.com:28000）→ 补默认路径
        return "ws://"+raw+(raw.endsWith("/")?"":"/")+path.replaceFirst("/","");
    }
    private void testBridge(String url,String token,java.util.function.Consumer<String> done){
        testBridgeStatic(this,url,token,done);
    }
    /** 供设置页独立测试连接（不依赖服务实例）。 */
    public static void testBridgeStatic(Context ctx,String url,String token,java.util.function.Consumer<String> done){
        final WhaleBridge[] holder=new WhaleBridge[1];
        final Handler h=new Handler(Looper.getMainLooper());
        final String[] failReason={null};
        holder[0]=new WhaleBridge(url,token,new WhaleBridge.Listener(){
            @Override public void onReady(String model){holder[0].close();done.accept(null);}
            @Override public void onDelta(String d){}
            @Override public void onDone(String t){}
            @Override public void onError(String m){holder[0].close();done.accept(m);}
            @Override public void onDisconnected(){done.accept("连不上服务器"+(failReason[0]!=null?"："+failReason[0]:"，检查地址/网络"));}
            @Override public void onDisconnectReason(String reason){failReason[0]=reason;}
        });
        holder[0].connect();
        h.postDelayed(()->{if(!holder[0].isReady()){holder[0].close();done.accept("连接超时（20秒）。地址对吗？手机网络能访问该域名吗？");}},20000);
    }
    private void updateNotification(){getSystemService(NotificationManager.class).notify(7,notification());}
    private void dismissMenu(){if(menu==null)return;if(!ValueAnimator.areAnimatorsEnabled()){hideMenu();return;}menu.animate().alpha(0).scaleX(.98f).scaleY(.98f).setDuration(120).withEndAction(this::hideMenu).start();}
    private void hideMenu(){if(menu!=null){makeMenuFocusable(false);try{wm.removeView(menu);}catch(IllegalArgumentException ignored){}menu=null;if(engine!=null&&engine.active)engine.menu(false);}}
    private void openApp(){hideMenu();startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
    public void stopPet(){store.prefs.edit().putBoolean("enabled",false).apply();stopSelf();}
    public JSONObject diagnostics(){JSONObject d=new JSONObject();try{d.put("session",session).put("window",root==null?0:System.identityHashCode(root)).put("screenActive",screenActive).put("attached",root!=null&&root.isAttachedToWindow()).put("draws",pet==null?0:pet.draws).put("lastDrawAt",pet==null?0:pet.lastDrawAt).put("animationRunning",pet!=null&&pet.animationRunning()).put("loaded",pet!=null&&pet.decoded).put("clip",pet==null?"":pet.currentId).put("taps",taps).put("holds",holds).put("sleeps",sleeps).put("menu",menu!=null).put("mirrored",pet!=null&&pet.isMirrored()).put("motionTarget",motionTarget).put("bubble",bubbleText).put("anchorX",worldX).put("anchorY",worldY).put("x",lp==null?0:lp.x).put("y",lp==null?0:lp.y).put("at",SystemClock.elapsedRealtime());}catch(JSONException ignored){}return d;}
    void snapshot(){if(store!=null)store.prefs.edit().putString("native-diagnostics",diagnostics().toString()).apply();}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent recover=PendingIntent.getService(this,2,new Intent(this,PetService.class).setAction("RECOVER"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT),stop=PendingIntent.getService(this,1,new Intent(this,PetService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT),chat=PendingIntent.getService(this,3,new Intent(this,PetService.class).setAction("CHAT"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT),cfg=PendingIntent.getService(this,4,new Intent(this,PetService.class).setAction("SETTINGS"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String state=bridge==null?"未配置":(bridge.isReady()?"已连接":"连接中…");
        return new Notification.Builder(this,STATUS_CHANNEL).setOnlyAlertOnce(true).setSound(null).setVibrate(null).setDefaults(0).setCategory(Notification.CATEGORY_SERVICE).setSmallIcon(R.drawable.ic_bubble_notification).setContentTitle(store.name()+"正在陪伴你 · 鲸宝"+state).setContentText("轻触回应 · 长按说话 · 通知栏可打字").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"打字聊天",chat).build()).addAction(new Notification.Action.Builder(null,"连接设置",cfg).build()).addAction(new Notification.Action.Builder(null,"待会儿见",stop).build()).build();
    }
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);hideMenu();applySettings();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);hideMenu();if(voice!=null)voice.destroy();if(bridge!=null)bridge.close();if(engine!=null)engine.dispose();if(pet!=null){savePosition();pet.release();try{wm.removeViewImmediate(root);}catch(IllegalArgumentException ignored){}}if(registered)unregisterReceiver(screen);instance=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
