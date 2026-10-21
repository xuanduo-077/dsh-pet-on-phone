package com.whale.pet;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Entire app UI is native Android. Existing assets and save keys stay unchanged. */
public final class MainActivity extends Activity implements PetEngine.Host {
    static volatile boolean visible;
    private PetStore store;private PetCatalog catalog;private PetEngine engine;private Ui.Palette palette;
    private LinearLayout shell,nav,homeMeters;private FrameLayout pageHost;private PetAnimationView heroPet,dialogPet;
    private TextView launchTitle,sceneStatus;private Ui.Bubble homeBubble;private Dialog actionDialog;private int page;private boolean journalAll;private int bubbleTicket;
    private android.widget.FrameLayout drawerHost;private LinearLayout drawerBody;private TextView drawerStatus;private View drawerScrim;
    private final Map<String,TextView> homeModes=new HashMap<>();
    private android.webkit.WebView web;private ScrollView chatScroll;private LinearLayout chatList;private int chatTicket;
    private boolean pendingStart,resumed;private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){@Override public void run(){if(resumed){if(page!=0){refreshHome();updateDrawer();}handler.postDelayed(this,10000);}}};
    private int dp(float n){return Ui.dp(this,n);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);store=new PetStore(this);catalog=new PetCatalog(this);engine=new PetEngine(store,catalog,this);if(saved!=null)page=saved.getInt("page",0);build();}
    private void build(){
        if(heroPet!=null)heroPet.release();heroPet=null;palette=new Ui.Palette(store.dark());shell=Ui.column(this);shell.setBackgroundColor(palette.bg);
        drawerHost=new android.widget.FrameLayout(this);drawerHost.addView(shell,new android.widget.FrameLayout.LayoutParams(-1,-1));setContentView(drawerHost);
        shell.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.statusBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,insets.getInsets(WindowInsets.Type.ime()).bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),0);return insets;});shell.requestApplyInsets();
        pageHost=new FrameLayout(this);shell.addView(pageHost,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout top=Ui.row(this);top.setPadding(dp(12),dp(6),dp(12),dp(6));top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView menuBtn=Ui.iconButton(this,"journal","打开菜单",palette,()->openDrawer());menuBtn.setPadding(dp(10),dp(10),dp(10),dp(10));top.addView(menuBtn,new LinearLayout.LayoutParams(dp(44),dp(44)));
        TextView brand=Ui.text(this,"DeepSeek · "+store.name(),14,palette.ink,true);brand.setPadding(dp(6),0,0,0);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        ImageView chatBtn=Ui.iconButton(this,"chat","刷新会话",palette,()->{if(web!=null)web.reload();});chatBtn.setPadding(dp(10),dp(10),dp(10),dp(10));top.addView(chatBtn,new LinearLayout.LayoutParams(dp(44),dp(44)));
        shell.addView(top,new LinearLayout.LayoutParams(-1,-2));
        buildDrawer();
        renderPage();appearance();
    }
    /** 轻量侧滑抽屉（零依赖）：scrim + 平移动画。 */
    private void openDrawer(){if(drawerScrim==null)return;drawerScrim.setVisibility(View.VISIBLE);drawerScrim.animate().alpha(1).setDuration(180).start();
        drawerBody.setVisibility(View.VISIBLE);drawerBody.setTranslationX(-dp(290));drawerBody.animate().translationX(0).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    private void closeDrawer(){if(drawerScrim==null)return;drawerScrim.animate().alpha(0).setDuration(160).withEndAction(()->drawerScrim.setVisibility(View.GONE)).start();
        drawerBody.animate().translationX(-dp(290)).setDuration(180).withEndAction(()->drawerBody.setVisibility(View.GONE)).start();}
    private void buildDrawer(){
        drawerScrim=new View(this);drawerScrim.setBackgroundColor(0x66000000);drawerScrim.setVisibility(View.GONE);drawerScrim.setClickable(true);drawerScrim.setOnClickListener(v->closeDrawer());
        drawerHost.addView(drawerScrim,new android.widget.FrameLayout.LayoutParams(-1,-1));
        drawerBody=Ui.column(this);drawerBody.setBackgroundColor(palette.card);drawerBody.setPadding(dp(20),dp(28),dp(16),dp(20));drawerBody.setVisibility(View.GONE);drawerBody.setElevation(dp(8));
        LinearLayout head=Ui.row(this);head.setGravity(Gravity.CENTER_VERTICAL);
        Ui.Motif mark=new Ui.Motif(this,palette,"spark");head.addView(mark,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout titles=Ui.column(this);titles.setPadding(dp(12),0,0,0);
        Ui.add(titles,Ui.text(this,"泡泡鲸",17,palette.ink,true),-2,0);
        Ui.add(titles,Ui.text(this,"口袋里的海 × DeepSeek",10,palette.muted,false),-2,4);
        head.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        Ui.add(drawerBody,head,-2,0);
        Ui.rule(drawerBody,palette);
        LinearLayout items=Ui.column(this);
        String[] names={"会话","互动","足迹","设置"};
        String[] icons={"chat","heart","journal","settings"};
        for(int i=0;i<4;i++){final int target=i;
            LinearLayout item=Ui.row(this);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(14),dp(13),dp(14),dp(13));
            item.setBackground(Ui.ripple(this,page==target?palette.soft:Color.TRANSPARENT,palette.blue,14));
            ImageView ic=Ui.icon(this,icons[i],page==target?palette.blue:palette.muted);item.addView(ic,new LinearLayout.LayoutParams(dp(22),dp(22)));
            TextView label=Ui.text(this,names[i],14,page==target?palette.blue:palette.ink,page==target);label.setPadding(dp(14),0,0,0);item.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            item.setOnClickListener(v->{closeDrawer();if(page==target)return;page=target;engine.menu(page!=0);renderPage();});
            Ui.press(item);Ui.add(items,item,-2,0);
        }
        Ui.add(drawerBody,items,-2,6);
        Ui.rule(drawerBody,palette);
        drawerStatus=Ui.text(this,"鲸宝：未知",11,palette.muted,false);drawerStatus.setPadding(dp(2),dp(14),0,0);Ui.add(drawerBody,drawerStatus,-2,0);
        android.widget.FrameLayout.LayoutParams lp=new android.widget.FrameLayout.LayoutParams(dp(280),-1);lp.gravity=Gravity.LEFT;
        ScrollView scroll=new ScrollView(this);scroll.addView(drawerBody,new ScrollView.LayoutParams(-1,-2));drawerHost.addView(scroll,lp);
        drawerBody.getLayoutParams().width=dp(280);
    }
    private void updateDrawer(){if(drawerStatus==null)return;PetService svc=PetService.instance;
        boolean on=store.prefs.getBoolean("enabled",false);
        boolean ready=svc!=null&&svc.bridgeReady();
        drawerStatus.setText(!on?"鲸宝还没起床":ready?"鲸宝在线 · 长按她说说话":"鲸宝未连接 · 设置里配地址");
        drawerStatus.setTextColor(!on?palette.muted:ready?0xff2e8b57:0xffcc4444);}
    private void appearance(){getWindow().setStatusBarColor(palette.bg);getWindow().setNavigationBarColor(palette.bg);getWindow().getDecorView().setSystemUiVisibility(palette.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null){c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);c.hide(WindowInsets.Type.navigationBars());}}else getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    private LinearLayout scrollPage(){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);LinearLayout body=Ui.column(this);body.setPadding(dp(20),dp(18),dp(20),dp(12));scroll.addView(body,new ScrollView.LayoutParams(-1,-2));pageHost.addView(scroll,new FrameLayout.LayoutParams(-1,-1));return body;}
    private void title(LinearLayout body,String eyebrow,String title,String note){Ui.add(body,Ui.text(this,eyebrow,9,palette.muted,true),-2,12);Ui.add(body,Ui.text(this,title,27,palette.ink,true),-2,10);if(note!=null)Ui.add(body,Ui.text(this,note,12,palette.muted,false),-2,22);}
    private void renderPage(){
        if(heroPet!=null){heroPet.release();heroPet=null;}homeModes.clear();homeMeters=null;launchTitle=null;homeBubble=null;sceneStatus=null;
        for(int i=pageHost.getChildCount()-1;i>=0;i--){View cv=pageHost.getChildAt(i);if(cv!=web)pageHost.removeViewAt(i);}
        if(web!=null)web.setVisibility(View.GONE);
        if(page==0){engine.pause();webPage();}
        else if(page==1){LinearLayout body=Ui.column(this);body.setPadding(dp(20),dp(18),dp(20),0);pageHost.addView(body,new FrameLayout.LayoutParams(-1,-1));LinearLayout heading=Ui.column(this);title(heading,"LITTLE JOYS / 今天的小计划","今天，一起做什么？",null);Ui.add(body,heading,-2,0);PetPanel panel=new PetPanel(this,store,catalog,new PetPanel.Host(){public void choose(PetCatalog.Action a,String token){playAction(a,token);}public void mode(String value){engine.mode(value);}public void message(String text){toast(text);}public void confirmation(boolean visible){heading.setVisibility(visible?View.GONE:View.VISIBLE);}},false);body.addView(panel,new LinearLayout.LayoutParams(-1,0,1));}
        else {LinearLayout body=scrollPage();if(page==2)journal(body);else settings(body);}
        if(page!=0){hideWeb();if(resumed)engine.resume();}
        Ui.enter(pageHost);
        if(resumed)engine.menu(page!=0);
    }
    private void home(LinearLayout body){
        String date=new SimpleDateFormat("M月d日 EEEE",Locale.CHINA).format(new Date());Ui.add(body,Ui.text(this,date+"  /  BY THE SEA",10,palette.muted,true),-2,18);
        LinearLayout greeting=Ui.row(this);TextView title=Ui.text(this,"把一小片海，\n装进口袋里。",27,palette.ink,true);title.setLineSpacing(dp(5),1);greeting.addView(title,new LinearLayout.LayoutParams(0,-2,1));Ui.Motif mark=new Ui.Motif(this,palette,"spark");greeting.addView(mark,new LinearLayout.LayoutParams(dp(68),dp(68)));Ui.add(body,greeting,-2,18);
        Ocean hero=new Ocean(this,palette);hero.setBackground(Ui.round(palette.dark?0xff304d80:0xffdceaff,0,32,this));hero.setClipToOutline(true);
        sceneStatus=Ui.text(this,"",11,palette.dark?0xffc6dafa:0xff5476aa,false);FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.LEFT);sp.setMargins(dp(18),dp(18),dp(18),0);hero.addView(sceneStatus,sp);
        TextView small=Ui.text(this,store.name()+"的小天地",10,palette.dark?0xffb8cdee:0xff6588b7,false);small.setMaxWidth(dp(180));small.setMaxLines(1);small.setEllipsize(android.text.TextUtils.TruncateAt.END);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT|Gravity.TOP);np.setMargins(0,dp(19),dp(18),0);hero.addView(small,np);
        heroPet=new PetAnimationView(this);heroPet.bodySize(dp(182));heroPet.setContentDescription("摸摸"+store.name());heroPet.setOnClickListener(v->engine.touch());Ui.press(heroPet);FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,-1);pp.topMargin=dp(24);pp.bottomMargin=dp(44);hero.addView(heroPet,pp);
        TextView caption=Ui.text(this,"轻触她，让心情泛起一点涟漪",11,palette.dark?0xffc1d4f4:0xff6689b9,false);caption.setGravity(Gravity.CENTER);FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(-1,dp(36),Gravity.BOTTOM);cp.bottomMargin=dp(5);hero.addView(caption,cp);
        homeBubble=new Ui.Bubble(this,palette);homeBubble.setVisibility(View.GONE);FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(dp(220),dp(57),Gravity.TOP|Gravity.LEFT);bp.setMargins(dp(12),dp(42),0,0);hero.addView(homeBubble,bp);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,dp(270),1);hp.bottomMargin=dp(18);body.addView(hero,hp);
        LinearLayout modes=Ui.row(this);modes.setBackground(Ui.round(palette.soft,0,22,this));modes.setPadding(dp(4),dp(4),dp(4),dp(4));for(String m:new String[]{"walking","resting"}){TextView b=Ui.labeled(this,m.equals("walking")?"日常":"休息",m.equals("walking")?"wave":"moon",palette,()->engine.mode(m));homeModes.put(m,b);Ui.weight(modes,b,2);}Ui.add(body,modes,-2,12);
        homeMeters=Ui.column(this);Ui.add(body,homeMeters,-2,16);
        LinearLayout launch=Ui.row(this);launch.setPadding(dp(18),dp(18),dp(18),dp(18));launch.setBackground(Ui.ripple(this,palette.dark?0xff294d8e:0xff3575df,Color.WHITE,24));
        LinearLayout words=Ui.column(this);launchTitle=Ui.text(this,"",16,Color.WHITE,true);launchTitle.setMaxLines(2);words.addView(launchTitle);TextView sub=Ui.text(this,"一起出发，把平凡日子过得可爱",10,0xffd2e3ff,false);sub.setPadding(0,dp(7),0,0);words.addView(sub);launch.addView(words,new LinearLayout.LayoutParams(0,-2,1));ImageView arrow=Ui.icon(this,"arrow",Color.WHITE);launch.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));launch.setOnClickListener(v->{if(store.prefs.getBoolean("enabled",false))stopPet();else startPet();});Ui.press(launch);Ui.add(body,launch,-2,0);refreshHome();engine.idle();
    }
    private void refreshHome(){String selected=PetStore.mode(store.read());for(Map.Entry<String,TextView> entry:homeModes.entrySet()){boolean on=entry.getKey().equals(selected);Ui.selected(entry.getValue(),on,palette);entry.getValue().setCompoundDrawables(Ui.symbol(this,entry.getKey().equals("walking")?"wave":"moon",on?(palette.dark?0xff102344:Color.WHITE):palette.muted,18),null,null,null);}if(homeMeters!=null){homeMeters.removeAllViews();homeMeters.addView(Ui.meters(this,store.read(),palette));}if(launchTitle!=null)launchTitle.setText(store.prefs.getBoolean("enabled",false)?store.name()+"正在陪伴你":"让"+store.name()+"来陪你");if(sceneStatus!=null)sceneStatus.setText(PetStore.mode(store.read()).equals("resting")?"慢慢休息":"自在日常");if(homeBubble!=null&&resumed&&page==0&&dialogPet==null)engine.chatTick();}
    private void playAction(PetCatalog.Action a,String token){
        String refused=store.refusal(a);if(refused!=null){toast(refused);return;}actionDialog=new Dialog(this);LinearLayout card=Ui.column(this);card.setPadding(dp(22),dp(22),dp(22),dp(18));card.setBackground(Ui.round(palette.card,palette.line,25,this));Ui.add(card,Ui.text(this,a.name,19,palette.ink,true),-2,8);dialogPet=new PetAnimationView(this);dialogPet.bodySize(dp(168));card.addView(dialogPet,new LinearLayout.LayoutParams(-1,dp(260)));Ui.add(card,Ui.text(this,store.name()+"收到你的心意啦",12,palette.muted,false),-2,16);Ui.add(card,Ui.button(this,"收起动画",palette,()->actionDialog.dismiss()),-2,0);actionDialog.setContentView(card);Window window=actionDialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setLayout(Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(32)),-2);}actionDialog.setOnDismissListener(d->{if(dialogPet!=null){dialogPet.release();dialogPet=null;}engine.menu(page!=0);refreshHome();});actionDialog.show();if(window!=null)window.setLayout(Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(32)),-2);engine.choose(a,true,token);
    }
    /** Viewing a memory never enters the interaction engine or writes pet state. */
    private void previewAction(PetCatalog.Action a){
        if(a==null||(actionDialog!=null&&actionDialog.isShowing()))return;
        actionDialog=new Dialog(this);LinearLayout card=Ui.column(this);card.setPadding(dp(20),dp(20),dp(20),dp(16));card.setBackground(Ui.round(palette.card,palette.line,24,this));
        TextView caption=Ui.text(this,"动作回看",10,palette.blue,true);caption.setGravity(Gravity.CENTER);Ui.add(card,caption,-2,12);
        TextView title=Ui.text(this,a.name,19,palette.ink,true);title.setGravity(Gravity.CENTER);Ui.add(card,title,-2,4);
        dialogPet=new PetAnimationView(this);dialogPet.setContentDescription("回看动画："+a.name);dialogPet.bodySize(dp(168));card.addView(dialogPet,new LinearLayout.LayoutParams(-1,dp(236)));
        Ui.add(card,Ui.button(this,"收起回看",palette,()->actionDialog.dismiss()),44,0);actionDialog.setContentView(card);
        Window window=actionDialog.getWindow();if(window!=null)window.setBackgroundDrawableResource(android.R.color.transparent);
        actionDialog.setOnDismissListener(d->{if(dialogPet!=null){dialogPet.release();dialogPet=null;}actionDialog=null;});
        actionDialog.show();if(window!=null)window.setLayout(Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(32)),-2);
        dialogPet.show(catalog.clips.get(a.id),()->{});Ui.enter(card);
    }
    private void journal(LinearLayout body){
        title(body,"LITTLE MEMORIES / 一起走过的日常","把小日子，慢慢收藏。","每个小瞬间，都可以点开回看。");
        JSONArray events=store.read().optJSONArray("events");PetJournal.Summary today=PetJournal.today(events,System.currentTimeMillis());
        LinearLayout overview=Ui.column(this);overview.setPadding(dp(16),dp(16),dp(16),dp(16));overview.setBackground(Ui.round(palette.soft,0,20,this));
        TextView heading=Ui.text(this,"今日陪伴",12,palette.ink,true);heading.setGravity(Gravity.CENTER);Ui.add(overview,heading,-2,16);
        LinearLayout totals=Ui.row(this);int[] counts={today.feed,today.play,today.actions};String[] labels={"一起吃饭","一起玩乐","点播动作"};for(int i=0;i<3;i++){LinearLayout cell=Ui.column(this);cell.setGravity(Gravity.CENTER);TextView number=Ui.text(this,Integer.toString(counts[i]),24,palette.ink,true);number.setGravity(Gravity.CENTER);Ui.add(cell,number,-2,6);TextView label=Ui.text(this,labels[i],10,palette.muted,false);label.setGravity(Gravity.CENTER);Ui.add(cell,label,-2,0);totals.addView(cell,new LinearLayout.LayoutParams(0,-2,1));}Ui.add(overview,totals,-2,16);
        JSONObject changes=new JSONObject();try{changes.put("satiety",today.satiety).put("mood",today.mood).put("energy",today.energy);}catch(JSONException ignored){}
        Ui.rule(overview,palette);String actual=PetJournal.effects(changes);TextView effects=Ui.text(this,actual.isEmpty()?"下一次互动的收获，会记在这里。":"互动带来的变化  "+actual,11,palette.blue,false);effects.setPadding(0,dp(12),0,0);Ui.add(overview,effects,-2,0);Ui.add(body,overview,-2,18);
        LinearLayout toolbar=Ui.row(this);TextView recordTitle=Ui.text(this,"小小回忆",12,palette.ink,true);toolbar.addView(recordTitle,new LinearLayout.LayoutParams(0,-2,1));toolbar.addView(Ui.filters(this,new String[]{"互动记录","全部记录"},journalAll?1:0,palette,index->{journalAll=index==1;renderPage();}));Ui.add(body,toolbar,44,4);
        if(!today.favoriteName.isEmpty()){LinearLayout favorite=Ui.row(this);ImageView spark=Ui.icon(this,"spark",palette.blue);favorite.addView(spark,new LinearLayout.LayoutParams(dp(14),dp(14)));TextView note=Ui.text(this,"今天偏爱  ·  "+today.favoriteName+" ×"+today.favoriteCount,10,palette.muted,false);note.setSingleLine(true);note.setEllipsize(android.text.TextUtils.TruncateAt.END);note.setPadding(dp(6),0,0,0);favorite.addView(note,new LinearLayout.LayoutParams(0,-2,1));Ui.add(body,favorite,24,4);}
        List<PetJournal.Entry> entries=PetJournal.entries(events,journalAll);
        if(entries.isEmpty()){TextView note=Ui.text(this,"还没有互动记录。一起吃顿饭，或玩一小会儿吧。",12,palette.muted,false);note.setPadding(0,dp(16),0,dp(16));Ui.add(body,note,-2,12);Ui.add(body,Ui.button(this,"去互动",palette,()->{page=1;renderPage();}),44,0);return;}
        String previous="";SimpleDateFormat day=new SimpleDateFormat("M月d日 EEEE",Locale.CHINA),clock=new SimpleDateFormat("HH:mm",Locale.CHINA);
        for(PetJournal.Entry entry:entries){JSONObject e=entry.event;Date at=new Date(e.optLong("at"));String group=day.format(at);if(!group.equals(previous)){TextView date=Ui.text(this,group,13,palette.ink,true);date.setPadding(0,dp(12),0,dp(8));Ui.add(body,date,-2,0);previous=group;}
            LinearLayout row=Ui.row(this);row.setPadding(0,dp(9),0,dp(9));row.setMinimumHeight(dp(52));TextView time=Ui.text(this,clock.format(at),10,palette.muted,false);row.addView(time,new LinearLayout.LayoutParams(dp(46),-2));LinearLayout words=Ui.column(this);
            String name=e.optString("name",e.optString("food",e.optString("toy","一份小小的陪伴")));if(e.optString("kind").equals("mode"))name=e.optString("mode").equals("resting")?"切换为休息":"切换为日常";
            Ui.add(words,Ui.text(this,name+(entry.count>1?" ×"+entry.count:""),13,palette.ink,true),-2,0);String effect=PetJournal.effects(entry.effects);if(!effect.isEmpty()){TextView sub=Ui.text(this,effect,10,palette.muted,false);sub.setPadding(0,dp(6),0,0);words.addView(sub);}row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
            PetCatalog.Action action=catalog.actions.get(e.optString("actionId"));if(action!=null){ImageView replay=Ui.iconButton(this,"play","回看动作："+action.name,palette,()->previewAction(action));row.addView(replay,new LinearLayout.LayoutParams(dp(44),dp(44)));}
            Ui.add(body,row,-2,0);Ui.rule(body,palette);
        }
        TextView scope=Ui.text(this,"珍藏最近 100 条记录",10,palette.muted,false);scope.setGravity(Gravity.CENTER);scope.setPadding(0,dp(18),0,dp(8));Ui.add(body,scope,-2,0);
    }
    private LinearLayout settingsSection(LinearLayout body){
        LinearLayout section=Ui.column(this);section.setPadding(0,dp(24),0,dp(24));Ui.add(body,section,-2,0);return section;
    }
    /** 连接鲸宝卡片：地址/配对码/测试/状态 + 三场景引导。 */
    private void connectionCard(LinearLayout body){
        LinearLayout card=settingsSection(body);
        Ui.add(card,Ui.text(this,"连接鲸宝（DeepSeek）",15,palette.ink,true),-2,16);
        final boolean[] testing={false};
        TextView statusLine=Ui.text(this,statusText(),11,statusColor(),false);statusLine.setPadding(dp(2),dp(2),0,0);
        EditText urlIn=new EditText(this);urlIn.setSingleLine(true);urlIn.setTextSize(13);urlIn.setTextColor(palette.ink);urlIn.setHintTextColor(palette.muted);
        urlIn.setHint("ws://192.168.x.x:28000/whale-pet/ws 或 wss://你的域名/whale-pet/ws");
        urlIn.setText(store.prefs.getString("bridge-url",""));urlIn.setBackground(Ui.round(palette.soft,palette.line,14,this));urlIn.setPadding(dp(12),dp(10),dp(12),dp(10));
        Ui.add(card,urlIn,54,10);
        EditText tokenIn=new EditText(this);tokenIn.setSingleLine(true);tokenIn.setTextSize(13);tokenIn.setTextColor(palette.ink);tokenIn.setHintTextColor(palette.muted);
        tokenIn.setHint("配对码（插件 cordis.patch.yml 里的 token）");tokenIn.setText(store.prefs.getString("bridge-token",""));tokenIn.setBackground(Ui.round(palette.soft,palette.line,14,this));tokenIn.setPadding(dp(12),dp(10),dp(12),dp(10));
        Ui.add(card,tokenIn,54,8);
        LinearLayout row=Ui.row(this);
        TextView test=Ui.button(this,"测试连接",palette,()->{if(testing[0])return;String u=urlIn.getText().toString().trim(),tk=tokenIn.getText().toString().trim();
            if(u.isEmpty()){toast("先填服务器地址");return;}
            store.prefs.edit().putString("bridge-url",u).putString("bridge-token",tk).apply();
            testing[0]=true;statusLine.setText("连接中…");statusLine.setTextColor(palette.muted);
            PetService.testBridgeStatic(this,u,tk,msg->handler.post(()->{testing[0]=false;
                if(msg==null){statusLine.setText("✓ 连接成功！回桌面长按鲸鱼就能对话");statusLine.setTextColor(0xff2e8b57);}
                else{statusLine.setText("✗ "+msg);statusLine.setTextColor(0xffcc4444);}}));
        });test.setPadding(dp(10),dp(10),dp(10),dp(10));
        row.addView(test,new LinearLayout.LayoutParams(0,-2,1));
        TextView save=Ui.button(this,"保存",palette,()->{store.prefs.edit().putString("bridge-url",urlIn.getText().toString().trim()).putString("bridge-token",tokenIn.getText().toString().trim()).apply();toast("已保存，重启桌宠生效");});save.setPadding(dp(10),dp(10),dp(10),dp(10));
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(0,-2,1);sl.setMargins(dp(8),0,0,0);row.addView(save,sl);
        Ui.add(card,row,-2,10);
        Ui.add(card,statusLine,-2,4);
        Ui.rule(card,palette);
        Ui.add(card,Ui.text(this,"会话页地址（WebView 加载的 DSH 网页）",13,palette.ink,true),-2,14);
        EditText webIn=new EditText(this);webIn.setSingleLine(true);webIn.setTextSize(13);webIn.setTextColor(palette.ink);webIn.setHintTextColor(palette.muted);
        webIn.setHint("http://192.168.31.55:28000 或 https://你的域名");
        webIn.setText(store.prefs.getString("web-url",""));webIn.setBackground(Ui.round(palette.soft,palette.line,14,this));webIn.setPadding(dp(12),dp(10),dp(12),dp(10));
        Ui.add(card,webIn,54,8);
        Ui.add(card,Ui.button(this,"保存会话页地址",palette,()->{String v=webIn.getText().toString().trim();if(!v.isEmpty()){if(!v.startsWith("http"))v="http://"+v;store.prefs.edit().putString("web-url",v).apply();toast("已保存，回「会话」页生效");if(web!=null){web.loadUrl(v);}}}),54,0);
        Switch forceMobile=new Switch(this);forceMobile.setText("强制手机排版（找不回 ≡ 菜单时打开）");forceMobile.setTextSize(13);forceMobile.setTextColor(palette.ink);forceMobile.setChecked(store.prefs.getBoolean("web-force-mobile",true));
        forceMobile.setOnCheckedChangeListener((button,value)->{store.prefs.edit().putBoolean("web-force-mobile",value).apply();if(web!=null)web.reload();});Ui.add(card,forceMobile,54,6);
        Switch clean=new Switch(this);clean.setText("网页简洁模式（去掉主题皮肤干扰）");clean.setTextSize(13);clean.setTextColor(palette.ink);clean.setChecked(store.prefs.getBoolean("web-clean",true));
        clean.setOnCheckedChangeListener((button,value)->{store.prefs.edit().putBoolean("web-clean",value).apply();if(web!=null){if(!value)web.evaluateJavascript("(function(){var s=document.getElementById('whale-clean-css');if(s)s.remove();})();",null);web.reload();}});Ui.add(card,clean,54,4);
        Ui.rule(card,palette);
        TextView guide=Ui.text(this,"三种连接方式：\n① 在家同 WiFi（推荐）：填 ws://电脑IP:28000/whale-pet/ws\n② 固定域名（出门也能用）：自己的 Cloudflare 隧道 → wss://域名/whale-pet/ws\n③ 临时隧道（应急）：电脑端插件管理页点「开启隧道」，填生成的 wss 地址。注意部分网络会拦截临时隧道的 WebSocket，连不上就用方式①②。",11,palette.muted,false);guide.setLineSpacing(dp(4),1);guide.setPadding(0,dp(10),0,0);
        Ui.add(card,guide,-2,0);
    }
    private String statusText(){PetService svc=PetService.instance;boolean on=store.prefs.getBoolean("enabled",false);boolean ready=svc!=null&&svc.bridgeReady();
        return !on?"鲸宝还没起床（首页点「让她来陪你」）":ready?"● 已连接，长按桌面的鲸鱼说话":"● 未连接，填好地址点「测试连接」";}
    private int statusColor(){PetService svc=PetService.instance;boolean on=store.prefs.getBoolean("enabled",false);boolean ready=svc!=null&&svc.bridgeReady();
        return !on?palette.muted:ready?0xff2e8b57:0xffcc4444;}
    // ---------------- 聊天页（长按语音 + 打字，与桌面气泡共用 WhaleBridge） ----------------

    /** 会话页：WebView 加载 DSH 网页（dsh-bridge 手机优化）。 */
    private void webPage(){
        String base=store.prefs.getString("web-url","");
        if(base.isEmpty())base="http://192.168.31.55:28000";
        // 强制手机排版：dsh-bridge 检测到宿主已有顶栏时会「让位」，手机端就没了 ≡ 菜单；
        // 关掉美化插件后宿主顶栏回来了，正好触发让位 → 侧边栏打不开。用官方开关强制接管。
        final String url=store.prefs.getBoolean("web-force-mobile",true)?base+(base.contains("?")?"&":"?")+"dsh_bridge_tweaks=1":base;
        if(web==null){
            web=new android.webkit.WebView(this);
            android.webkit.WebSettings ws=web.getSettings();
            ws.setJavaScriptEnabled(true);ws.setDomStorageEnabled(true);ws.setUseWideViewPort(true);ws.setLoadWithOverviewMode(true);
            web.setWebViewClient(new android.webkit.WebViewClient(){
                @Override public void onPageFinished(android.webkit.WebView v,String u){
                    // 简洁模式：注入 CSS 抹平皮肤插件（uiskin-theme 等）对手机端的干扰——恢复系统字体、去掉海洋背景
                    if(store.prefs.getBoolean("web-clean",true)){
                        v.evaluateJavascript("(function(){if(document.getElementById('whale-clean-css'))return;var s=document.createElement('style');s.id='whale-clean-css';s.textContent=" +
                            "\"*:not(svg):not(svg *){font-family:'Segoe UI',-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif !important;}\" +" +
                            "\"html,body{background-image:none !important;}\" +" +
                            "\"body::before,body::after{background-image:none !important;content:none !important;}\";document.head.appendChild(s);})();",null);
                    }
                    // SPA 渲染较晚：延迟几轮探测网页自带的 ≡ 菜单是否在，不在就亮出我们自己的浮动按钮
                    handler.postDelayed(MainActivity.this::updateWebNav,700);
                    handler.postDelayed(MainActivity.this::updateWebNav,2200);
                }
            });
            web.setWebChromeClient(new android.webkit.WebChromeClient());
        }
        pageHost.addView(web,new FrameLayout.LayoutParams(-1,-1));
        if(pageHost.findViewWithTag("web-nav")==null){
            TextView nav=new TextView(this);
            nav.setTag("web-nav");nav.setText("\u2261");nav.setTextSize(20);nav.setTextColor(Color.WHITE);nav.setGravity(android.view.Gravity.CENTER);
            android.graphics.drawable.GradientDrawable gd=new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);gd.setColor(0x99000000);nav.setBackground(gd);
            nav.setVisibility(View.GONE);
            nav.setOnClickListener(v->toggleWebDrawer());
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(40),dp(40));
            lp.gravity=android.view.Gravity.TOP|android.view.Gravity.START;lp.setMargins(dp(8),dp(56),0,0);
            pageHost.addView(nav,lp);
        }
        String cur=web.getUrl();
        if(cur==null||!cur.startsWith(base)) web.loadUrl(url);
        pageHost.bringChildToFront(web);
        View nav=pageHost.findViewWithTag("web-nav");if(nav!=null)pageHost.bringChildToFront(nav);
        web.setVisibility(View.VISIBLE);
    }
    /** 网页自带 ≡ 在的话就藏起我们的浮动按钮（避免两个菜单叠在一起）。 */
    private void updateWebNav(){
        if(web==null||pageHost==null)return;
        final View nav=pageHost.findViewWithTag("web-nav");
        if(nav==null)return;
        web.evaluateJavascript("(function(){return !!document.querySelector('.dsh-header-menu-btn');})();",new android.webkit.ValueCallback<String>(){
            @Override public void onReceiveValue(String s){if(nav!=null)nav.setVisibility("true".equals(s)?View.GONE:View.VISIBLE);}
        });
    }
    /** 打开/关闭侧边栏：优先点网页自带的 ≡，否则直接驱动 dsh-bridge 的抽屉状态。 */
    private void toggleWebDrawer(){
        if(web==null)return;
        web.evaluateJavascript("(function(){" +
            "var b=document.querySelector('.dsh-header-menu-btn');if(b){b.click();return;}" +
            "if(document.body.classList.contains('dsh-drawer-open')){document.body.classList.remove('dsh-drawer-open');return;}" +
            "document.body.classList.add('dsh-drawer-open');" +
            "var e=document.querySelector('button[aria-label*=\"打开侧边栏\"],button[title*=\"打开侧边栏\"],button[aria-label*=\"Open sidebar\"],button[title*=\"Open sidebar\"],button[aria-label*=\"展开侧边栏\"],button[title*=\"展开侧边栏\"]');" +
            "if(e)e.click();})();",null);
    }
    private void hideWeb(){if(web!=null)web.setVisibility(View.GONE);View nav=pageHost.findViewWithTag("web-nav");if(nav!=null)nav.setVisibility(View.GONE);}
    @Override public boolean onKeyDown(int keyCode,android.view.KeyEvent event){
        if(keyCode==android.view.KeyEvent.KEYCODE_BACK&&web!=null&&page==0&&web.canGoBack()){web.goBack();return true;}
        return super.onKeyDown(keyCode,event);
    }

    /** DeepSeek 风格聊天页（保留备用）：空会话=居中欢迎+大输入卡；有消息=列表+常驻底栏输入卡。 */
    private void chatPage(){
        LinearLayout body=Ui.column(this);
        org.json.JSONArray log=WhaleChat.read(store.prefs);
        boolean empty=log.length()==0;
        if(empty){
            // 空态：垂直居中
            FrameLayout center=new FrameLayout(this);
            LinearLayout middle=Ui.column(this);middle.setGravity(Gravity.CENTER);
            LinearLayout brand=Ui.row(this);brand.setGravity(Gravity.CENTER);
            ImageView whale=Ui.icon(this,"spark",palette.ink);whale.getLayoutParams().width=dp(34);whale.getLayoutParams().height=dp(34);
            TextView slogan=Ui.text(this,"探索未至之境",26,palette.ink,true);slogan.setPadding(dp(10),0,0,0);
            brand.addView(whale,new LinearLayout.LayoutParams(dp(34),dp(34)));brand.addView(slogan,new LinearLayout.LayoutParams(-2,-2));
            Ui.add(middle,brand,-2,0);
            TextView sub=Ui.text(this,"你好，我是"+store.name()+" —— 有什么想聊的？",13,palette.muted,false);sub.setPadding(0,dp(12),0,0);
            Ui.add(middle,sub,-2,0);
            FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER);mp.setMargins(dp(24),0,dp(24),dp(120));
            center.addView(middle,mp);
            // 大输入卡固定底部
            center.addView(chatInputCard(false),new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            pageHost.addView(center,new FrameLayout.LayoutParams(-1,-1));
            chatList=null;chatScroll=null;
            return;
        }
        // 会话态
        chatScroll=new ScrollView(this);chatScroll.setFillViewport(false);chatScroll.setClipToPadding(false);
        chatList=Ui.column(this);chatList.setPadding(dp(16),dp(10),dp(16),dp(12));chatScroll.addView(chatList,new ScrollView.LayoutParams(-1,-2));
        body.addView(chatScroll,new LinearLayout.LayoutParams(-1,0,1));
        body.addView(chatInputCard(true),new LinearLayout.LayoutParams(-1,-2));
        pageHost.addView(body,new FrameLayout.LayoutParams(-1,-1));
        renderChat();
    }
    /** 底部输入卡：圆角大输入框 + 发送圆钮 + 模型名。expanded=true 时铺满宽度（会话态）。 */
    private View chatInputCard(boolean expanded){
        LinearLayout card=Ui.column(this);
        card.setBackground(Ui.round(palette.soft,palette.line,22,this));card.setPadding(dp(14),dp(12),dp(12),dp(10));
        FrameLayout.LayoutParams lp=expanded?new FrameLayout.LayoutParams(-1,-2):new FrameLayout.LayoutParams(-1,-2);
        if(!expanded){lp.setMargins(dp(16),0,dp(16),dp(18));card.setLayoutParams(lp);}
        else{LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-1,-2);cl.setMargins(dp(12),dp(6),dp(12),dp(10));card.setLayoutParams(cl);}
        EditText input=new EditText(this);input.setHint("给"+store.name()+"发消息");input.setMaxLines(4);input.setTextSize(15);input.setTextColor(palette.ink);input.setHintTextColor(palette.muted);input.setBackground(null);input.setPadding(dp(4),dp(2),dp(4),dp(6));
        card.addView(input,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout row=Ui.row(this);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView model=Ui.text(this,"鲸宝 · DSH",12,palette.muted,false);model.setPadding(dp(4),0,0,0);
        row.addView(model,new LinearLayout.LayoutParams(0,-2,1));
        TextView send=Ui.text(this,"↑",16,Color.WHITE,true);send.setGravity(Gravity.CENTER);
        send.setBackground(Ui.round(palette.blue,0,22,this));send.setContentDescription("发送");
        send.setOnClickListener(v->{
            String t=input.getText().toString().trim();if(t.isEmpty())return;input.setText("");
            PetService svc=PetService.instance;
            if(svc!=null){svc.sendFromApp(t);}else toast("鲸宝还没起床，先去设置里连接");
        });
        row.addView(send,new LinearLayout.LayoutParams(dp(38),dp(38)));
        card.addView(row,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }
    private void renderChat(){
        if(chatList==null)return;
        chatList.removeAllViews();
        org.json.JSONArray log=WhaleChat.read(store.prefs);
        if(log.length()==0){Ui.add(chatList,Ui.text(this,"还没有对话。长按桌面上的鲸鱼，或在这里打字。",12,palette.muted,false),-2,18);return;}
        for(int i=0;i<log.length();i++){
            org.json.JSONObject o=log.optJSONObject(i);if(o==null)continue;
            boolean mine=o.optString("r").equals("u");
            String text=o.optString("t");
            long at=o.optLong("at");
            LinearLayout row=Ui.row(this);
            TextView card=Ui.text(this,text,13,mine?palette.ink:palette.ink,false);
            card.setTextSize(13);card.setTextIsSelectable(true);
            card.setBackground(Ui.round(mine?palette.blue:palette.soft,0,16,this));
            if(mine){card.setTextColor(Color.WHITE);}
            card.setPadding(dp(12),dp(9),dp(12),dp(9));card.setMaxWidth(dp(240));
            LinearLayout.LayoutParams cl=new LinearLayout.LayoutParams(-2,-2);cl.weight=0;
            LinearLayout side=new LinearLayout(this);side.setOrientation(LinearLayout.HORIZONTAL);
            if(mine){TextView spacer=Ui.text(this,"",1,0,false);spacer.setLayoutParams(new LinearLayout.LayoutParams(0,1,1));side.addView(spacer);}
            side.addView(card,cl);
            LinearLayout spacer2=new LinearLayout(this);spacer2.setLayoutParams(new LinearLayout.LayoutParams(0,1,1));side.addView(spacer2);
            LinearLayout wrap=Ui.column(this);wrap.addView(side,new LinearLayout.LayoutParams(-1,-2));
            String time=new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(at));
            TextView meta=Ui.text(this,(mine?"我":"鲸宝")+" · "+time,9,palette.muted,false);meta.setGravity(mine?Gravity.RIGHT:Gravity.LEFT);meta.setPadding(0,dp(3),0,0);
            wrap.addView(meta,new LinearLayout.LayoutParams(-1,-2));
            Ui.add(chatList,wrap,-2,10);
        }
        chatList.post(()->{ScrollView parent=(ScrollView)chatList.getParent();if(parent!=null)parent.fullScroll(View.FOCUS_DOWN);});
    }
    private void settings(LinearLayout body){
        title(body,"SETTINGS / 设置","按你的节奏来","聊天、宠物与连接，都在这里。");
        connectionCard(body);
        LinearLayout pet=settingsSection(body);
        Ui.add(pet,Ui.text(this,"桌面小宠物",15,palette.ink,true),-2,16);
        Switch enable=new Switch(this);enable.setText("让她到桌面陪我");enable.setTextSize(14);enable.setTextColor(palette.ink);enable.setChecked(store.prefs.getBoolean("enabled",false));
        enable.setOnCheckedChangeListener((button,value)->{if(value)startPet();else stopPet();});Ui.add(pet,enable,56,4);
        heroPet=new PetAnimationView(this);heroPet.bodySize(dp(80+store.prefs.getInt("size",168)*.25f));heroPet.show(catalog.clips.get("idle"),()->{});heroPet.setBackground(Ui.round(palette.soft,0,16,this));Ui.add(pet,heroPet,158,16);
        TextView sizeLabel=Ui.text(this,"人物大小  "+store.prefs.getInt("size",168),13,palette.ink,false);Ui.add(pet,sizeLabel,-2,12);
        SeekBar size=new SeekBar(this);size.setMax(100);size.setProgress(store.prefs.getInt("size",168)-120);size.setPadding(dp(8),0,dp(8),0);size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar bar,int progress,boolean user){int value=120+progress/4*4;sizeLabel.setText("人物大小  "+value);if(heroPet!=null)heroPet.bodySize(dp(80+value*.25f));}public void onStartTrackingTouch(SeekBar bar){}public void onStopTrackingTouch(SeekBar bar){store.prefs.edit().putInt("size",120+bar.getProgress()/4*4).apply();settingsChanged();}});Ui.add(pet,size,48,12);
        Switch roam=new Switch(this);roam.setText("自在走动");roam.setTextSize(14);roam.setTextColor(palette.ink);roam.setChecked(store.prefs.getBoolean("roam",true));roam.setOnCheckedChangeListener((button,value)->{store.prefs.edit().putBoolean("roam",value).apply();settingsChanged();});Ui.add(pet,roam,56,0);
        Ui.rule(pet,palette);
        Switch dark=new Switch(this);dark.setText("深海夜色");dark.setTextSize(14);dark.setTextColor(palette.ink);dark.setChecked(store.dark());dark.setOnCheckedChangeListener((button,value)->{store.prefs.edit().putBoolean("dark",value).apply();engine.pause();build();if(resumed)engine.resume();settingsChanged();});Ui.add(pet,dark,56,0);
        Ui.rule(pet,palette);
        LinearLayout name=settingsSection(body);
        Ui.add(name,Ui.text(this,"给这份陪伴起个名字",15,palette.ink,true),-2,16);
        EditText input=new EditText(this);input.setText(store.name());input.setTextColor(palette.ink);input.setSingleLine(true);input.setTextSize(15);input.setHint("泡泡");input.setHintTextColor(palette.muted);input.setPadding(dp(16),dp(12),dp(16),dp(12));input.setBackground(Ui.round(palette.soft,0,16,this));Ui.add(name,input,48,12);
        Ui.add(name,Ui.button(this,"保存名字",palette,()->{String value=input.getText().toString().trim();if(value.isEmpty())value="泡泡";if(value.codePointCount(0,value.length())>12||value.matches(".*[\\p{Cntrl}].*")){toast("名字请用 1～12 个字");return;}store.prefs.edit().putString("name",value).apply();input.clearFocus();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);settingsChanged();toast("以后就叫"+value+"啦");}),48,0);
        Ui.rule(body,palette);
        LinearLayout tips=settingsSection(body);Ui.add(tips,Ui.text(this,"陪伴小贴士",15,palette.ink,true),-2,16);
        TextView guide=Ui.text(this,"桌面上：轻触回应，拖动调整位置，长按她说话（自动转文字，交给电脑上的 DeepSeek 回复）。\n\n打字聊天：回 App 聊天页，或下拉通知栏点「打字聊天」。\n\n关屏后停止动画绘制，陪伴服务保留；解锁后直接继续。\n\n跑动每 10 分钟最多消耗 1 精力，休息每小时恢复 30。饱食每 15 分钟减少 1，满 100 时先歇歇肚子。",12,palette.muted,false);guide.setLineSpacing(dp(4),1);Ui.add(tips,guide,-2,16);
        Ui.add(tips,Ui.button(this,"查看桌面陪伴权限",palette,()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))),48,12);
        TextView permissions=Ui.text(this,"小米 / Redmi：检查显示悬浮窗、后台弹出界面、自启动与后台运行限制。",11,palette.muted,false);permissions.setLineSpacing(dp(4),1);Ui.add(tips,permissions,-2,0);
        Ui.rule(body,palette);
        LinearLayout about=settingsSection(body);Ui.add(about,Ui.text(this,"泡泡鲸 · 口袋里的海 × DeepSeek\n4.0 / WHALE PET ON PHONE",12,palette.muted,true),-2,16);
        Ui.add(about,Ui.button(this,"素材与开源致谢",palette,()->new AlertDialog.Builder(this).setTitle("素材与开源致谢").setMessage("角色动画：dsh-pet / PC2005-cloud，MIT。\n原生桌宠框架：duoxichangan/whale-pet-Android（泡泡鲸 2.3.0），MIT。\nDSH 桥接插件：dsh-whale-bridge，MIT（github.com/xuanduo-077/dsh-pet-on-phone）。\n语音识别使用 Android 系统服务，文字出本机才发给你的 DSH。\n\n完整许可随源码提供。").setPositiveButton("知道了",null).show()),48,0);
    }
    private void settingsChanged(){if(PetService.instance!=null)PetService.instance.applySettings();refreshHome();}
    private void startPet(){if(!Settings.canDrawOverlays(this)){pendingStart=true;startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));return;}if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},9);if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);store.prefs.edit().putBoolean("enabled",true).apply();try{startForegroundService(new Intent(this,PetService.class));}catch(Exception e){store.prefs.edit().putBoolean("enabled",false).apply();toast("暂时无法开启桌宠，请重试");}refreshHome();}
    private void stopPet(){store.prefs.edit().putBoolean("enabled",false).apply();stopService(new Intent(this,PetService.class));refreshHome();}
    private void toast(String text){android.widget.Toast.makeText(this,text,Toast.LENGTH_SHORT).show();}
    @Override protected void onResume(){super.onResume();resumed=visible=true;if(PetService.instance!=null)PetService.instance.updateVisibility();engine.resume();engine.menu(page!=0);appearance();handler.post(refresh);handler.post(chatTick);if(pendingStart){pendingStart=false;if(Settings.canDrawOverlays(this))startPet();}else if(store.prefs.getBoolean("enabled",false)&&PetService.instance==null)startPet();}
    private final Runnable chatTick=new Runnable(){@Override public void run(){if(!resumed)return;if(false){int ticket=++chatTicket;int latest=PetService.chatVersion;boolean dirty=latest!=ticket-1;if(latest!=chatTicketSeen){chatTicketSeen=latest;renderChat();}}handler.postDelayed(this,600);}};
    private int chatTicketSeen;
    @Override protected void onPause(){resumed=visible=false;engine.pause();handler.removeCallbacks(refresh);handler.removeCallbacks(chatTick);if(actionDialog!=null&&actionDialog.isShowing())actionDialog.dismiss();super.onPause();if(PetService.instance!=null)PetService.instance.updateVisibility();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);engine.dispose();if(heroPet!=null)heroPet.release();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",page);super.onSaveInstanceState(out);}
    @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(focus&&palette!=null)appearance();}
    @Override public void onBackPressed(){if(page!=0){page=0;renderPage();}else moveTaskToBack(true);}
    @Override public void show(String id,Runnable loaded){PetAnimationView target=dialogPet!=null?dialogPet:heroPet;if(target!=null){target.mirror(false);target.show(catalog.clips.get(id),loaded);}else loaded.run();}
    @Override public void active(boolean value){if(heroPet!=null)heroPet.screenActive(value);if(dialogPet!=null)dialogPet.screenActive(value);}
    @Override public void message(String text){engine.noteSpeech(text);if(homeBubble!=null&&page==0&&dialogPet==null){int ticket=++bubbleTicket;homeBubble.text(text);homeBubble.setVisibility(View.VISIBLE);handler.postDelayed(()->{if(ticket==bubbleTicket&&homeBubble!=null)homeBubble.setVisibility(View.GONE);},7000);}else toast(text);}
    @Override public void changed(){refreshHome();}
    @Override public boolean move(boolean right,int duration){if(heroPet==null||page!=0||dialogPet!=null)return false;float center=heroPet.getWidth()/2f,limit=Math.max(0,center-dp(78));heroPet.faceToward(right);android.animation.ValueAnimator animation=android.animation.ValueAnimator.ofFloat(center,right?center+limit:center-limit);homeMotion=animation;animation.setDuration(duration);animation.setInterpolator(new android.view.animation.LinearInterpolator());animation.addUpdateListener(v->{if(heroPet!=null)heroPet.anchor((float)v.getAnimatedValue(),heroPet.getHeight()*.88f);});animation.start();return true;}
    private android.animation.ValueAnimator homeMotion;
    @Override public void stopMotion(){if(homeMotion!=null){homeMotion.cancel();homeMotion=null;}if(heroPet!=null){heroPet.anchor(Float.NaN,Float.NaN);heroPet.mirror(false);}}
    private static final class Ocean extends FrameLayout{
        private final Paint paint=new Paint(3);private final Ui.Palette p;private float phase;private long epoch;private boolean awake;
        private final Runnable tide=new Runnable(){public void run(){if(!awake||!isShown()||getWindowVisibility()!=VISIBLE||!android.animation.ValueAnimator.areAnimatorsEnabled())return;phase=(android.os.SystemClock.uptimeMillis()-epoch)/9000f;invalidate();postDelayed(this,66);}};
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();awake=true;epoch=android.os.SystemClock.uptimeMillis();post(tide);}
        @Override protected void onDetachedFromWindow(){awake=false;removeCallbacks(tide);super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(tide!=null){removeCallbacks(tide);if(visibility==VISIBLE&&awake)post(tide);}}
        Ocean(Context c,Ui.Palette p){super(c);this.p=p;setWillNotDraw(false);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();paint.setShader(new LinearGradient(0,0,w,h,p.dark?0xff293f6b:0xffe1edff,p.dark?0xff466da7:0xffb2d0ff,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);paint.setColor(p.dark?0x123c65a3:0x55ffffff);c.drawCircle(w*.75f,h*.28f,w*(.16f+(float)Math.sin(phase)*.009f),paint);paint.setColor(p.dark?0x33447ab4:0x33628fe0);c.drawOval(-w*.3f+(float)Math.sin(phase)*w*.08f,h*.66f,w*1.6f,h*1.3f,paint);paint.setColor(p.dark?0x334f87c4:0x335a8bd3);c.drawOval(-w*.6f,h*.8f,w*1.4f,h*1.5f,paint);paint.setColor(0xaaffffff);for(int i=0;i<5;i++){float x=w*(.12f+i*.18f),y=h*(.19f+i*.125f)+(float)Math.sin(phase+i)*Ui.dp(getContext(),5);c.drawCircle(x,y,Ui.dp(getContext(),i%2==0?2:1),paint);}}
    }
}
