package dev.midrop;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

public class MainActivity extends Activity {
    static volatile boolean visible=false;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Skin skin;
    private LinearLayout devices,fileArea,progressArea;
    private TextView connectionLabel,progressTitle,progressDetail;
    private ProgressBar progress;
    private Button cancel;
    private List<Discovery.Device> nearby=new ArrayList<>();
    private Discovery.Listener discovery;
    private String deviceStamp="",lastState="",shownOffer="";
    private Dialog offerDialog;
    private boolean connecting;
    private final Runnable poll=new Runnable() { public void run() {
        if (!visible) return;
        refreshDevices(false); refreshProgress(); showOffer(); main.postDelayed(this,500);
    }};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); skin=new Skin(this);
        String previous=Protocol.prefs(this).getString("transfer_state","");
        if ((previous.equals("preparing") || previous.equals("transferring")) && !TransferService.ACTIVE.get() && !SessionService.RECEIVING.get())
            Protocol.progress(this,Protocol.prefs(this).getString("transfer_direction",""),"failed",Protocol.prefs(this).getString("transfer_name",""),0,0,"上次传输已中断，请重新发送");
        getWindow().setStatusBarColor(skin.bg); getWindow().setNavigationBarColor(skin.bg);
        getWindow().getDecorView().setSystemUiVisibility(skin.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(skin.bg);
        LinearLayout root=skin.column(); root.setPadding(skin.dp(22),skin.dp(20),skin.dp(22),skin.dp(24)); scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v,insets) -> {
            int top,bottom;
            if (Build.VERSION.SDK_INT>=30) { android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()); top=bars.top; bottom=bars.bottom; }
            else { top=insets.getSystemWindowInsetTop(); bottom=insets.getSystemWindowInsetBottom(); }
            root.setPadding(skin.dp(22),top+skin.dp(20),skin.dp(22),bottom+skin.dp(24)); return insets;
        });
        setContentView(scroll);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this); logo.setImageResource(R.drawable.ic_foreground); logo.setBackground(skin.shape(android.graphics.Color.parseColor("#12211B"),12)); logo.setPadding(skin.dp(3),skin.dp(3),skin.dp(3),skin.dp(3)); header.addView(logo,new LinearLayout.LayoutParams(skin.dp(44),skin.dp(44)));
        TextView title=skin.text("MiDrop",24,skin.text); title.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1); titleParams.leftMargin=skin.dp(12); header.addView(title,titleParams);
        ImageButton settings=new ImageButton(this); settings.setImageResource(R.drawable.ic_more);
        settings.setImageTintList(android.content.res.ColorStateList.valueOf(skin.muted));
        settings.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(skin.dark?0x24FFFFFF:0x18000000),null,skin.shape(android.graphics.Color.WHITE,24)));
        settings.setPadding(skin.dp(12),skin.dp(12),skin.dp(12),skin.dp(12));
        settings.setScaleType(ImageView.ScaleType.CENTER_INSIDE); settings.setContentDescription("设置");
        settings.setOnClickListener(v -> settings()); header.addView(settings,new LinearLayout.LayoutParams(skin.dp(48),skin.dp(48))); root.addView(header);
        skin.gap(root,28);
        TextView subtitle=skin.text("在你的设备之间，轻松传递。",15,skin.muted); root.addView(subtitle);
        skin.gap(root,24);
        fileArea=skin.column(); root.addView(fileArea); renderFiles();
        skin.gap(root,26);
        LinearLayout labelRow=new LinearLayout(this); labelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=skin.text("你的设备",16,skin.text); label.setTypeface(null,Typeface.BOLD); labelRow.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        connectionLabel=skin.text("正在发现",12,skin.muted); labelRow.addView(connectionLabel); root.addView(labelRow); skin.gap(root,12);
        devices=skin.column(); root.addView(devices); refreshDevices(true);
        skin.gap(root,22);
        progressArea=skin.card(); progressArea.setVisibility(View.GONE); root.addView(progressArea);
        progressTitle=skin.text("",16,skin.text); progressTitle.setTypeface(null,Typeface.BOLD); progressArea.addView(progressTitle); skin.gap(progressArea,10);
        progressDetail=skin.text("",13,skin.muted); progressDetail.setMaxLines(4); progressArea.addView(progressDetail);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(skin.mint));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,skin.dp(5)); pp.topMargin=skin.dp(16); progressArea.addView(progress,pp);
        cancel=skin.button("取消传输",false); cancel.setOnClickListener(v -> {
            String direction=Protocol.prefs(this).getString("transfer_direction","");
            if (direction.equals("incoming")) startService(new Intent(this,SessionService.class).setAction("CANCEL"));
            else if (TransferService.ACTIVE.get()) startService(new Intent(this,TransferService.class).setAction("CANCEL"));
        }); progressArea.addView(cancel);
        discovery=list -> { nearby=list; refreshDevices(true); };
        Protocol.clientId(this);
    }
    @Override protected void onStart() { super.onStart(); visible=true; Discovery.get(this).add(discovery); SessionService.start(this); SessionService.visibilityChanged(); main.post(poll); }
    @Override protected void onStop() {
        visible=false; main.removeCallbacks(poll); Discovery.get(this).remove(discovery); SessionService.visibilityChanged();
        if (!Protocol.prefs(this).getBoolean("background_receive",true) && !SessionService.RECEIVING.get()) stopService(new Intent(this,SessionService.class));
        super.onStop();
    }
    @Override protected void onDestroy() { worker.shutdownNow(); if (offerDialog!=null) offerDialog.dismiss(); super.onDestroy(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); renderFiles(); }
    private boolean hasFiles() { return !TransferService.uris(getIntent()).isEmpty() || getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT)!=null; }
    private void renderFiles() {
        fileArea.removeAllViews(); LinearLayout card=skin.card(); fileArea.addView(card);
        ArrayList<Uri> uris=TransferService.uris(getIntent());
        if (!hasFiles()) {
            TextView title=skin.text("准备分享",21,skin.text); title.setTypeface(null,Typeface.BOLD); card.addView(title); skin.gap(card,8);
            card.addView(skin.text("照片、视频或文件，保持原始内容。",14,skin.muted));
            Button choose=skin.button("选择文件",true); choose.setOnClickListener(v -> choose()); card.addView(choose);
        } else {
            String heading=uris.isEmpty()?"一段文字":uris.size()+" 个文件已就绪";
            TextView title=skin.text(heading,20,skin.text); title.setTypeface(null,Typeface.BOLD); card.addView(title); skin.gap(card,10);
            for (int i=0;i<Math.min(3,uris.size());i++) {
                String name=uris.get(i).getLastPathSegment();
                try (android.database.Cursor c=getContentResolver().query(uris.get(i),new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) { if (c!=null && c.moveToFirst()) name=c.getString(0); } catch (Exception ignored) {}
                TextView row=skin.text(name==null?"文件":name,14,skin.muted); row.setMaxLines(1); row.setEllipsize(TextUtils.TruncateAt.MIDDLE); card.addView(row); skin.gap(card,5);
            }
            if (uris.size()>3) card.addView(skin.text("另有 "+(uris.size()-3)+" 个文件",13,skin.muted));
            if (uris.isEmpty()) { TextView t=skin.text(getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT).toString(),14,skin.muted); t.setMaxLines(2); t.setEllipsize(TextUtils.TruncateAt.END); card.addView(t); }
            skin.gap(card,14); card.addView(skin.text("点击下方设备即可发送",13,skin.muted));
            Button change=skin.button("更换文件",false); change.setOnClickListener(v -> choose()); card.addView(change);
        }
    }
    private void choose() {
        if (busy()) { toast("请等待当前传输完成"); return; }
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        startActivityForResult(i,21);
    }
    @Override protected void onActivityResult(int r,int result,Intent data) {
        super.onActivityResult(r,result,data);
        if (r==21 && result==RESULT_OK && data!=null) {
            Intent i=new Intent(Intent.ACTION_SEND_MULTIPLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (data.getClipData()!=null) i.setClipData(data.getClipData()); else i.putExtra(Intent.EXTRA_STREAM,data.getData());
            setIntent(i); renderFiles(); refreshDevices(true);
        }
    }
    private boolean busy() { return connecting || TransferService.ACTIVE.get() || SessionService.RECEIVING.get(); }
    private void refreshDevices(boolean force) {
        if (devices==null) return;
        boolean saved=!Protocol.prefs(this).getString("token","").isEmpty();
        boolean connected=Protocol.prefs(this).getBoolean("connected",false);
        String savedId=Protocol.serverId(this),savedAddress=Protocol.prefs(this).getString("address","");
        String stamp=saved+":"+connected+":"+savedAddress+":"+connecting;
        for (Discovery.Device d:nearby) stamp+=d.id+d.address+d.name;
        if (!force && stamp.equals(deviceStamp)) return;
        deviceStamp=stamp; devices.removeAllViews();
        connectionLabel.setText(connected?"已连接":"自动发现");
        List<Discovery.Device> list=new ArrayList<>(nearby);
        boolean foundSaved=false;
        for (Discovery.Device d:list) if (saved && (!d.id.isEmpty()?d.id.equals(savedId):d.address.equals(savedAddress))) foundSaved=true;
        if (saved && !foundSaved) list.add(0,new Discovery.Device(Protocol.prefs(this).getString("name","我的 Mac"),savedAddress,savedId));
        if (list.isEmpty()) {
            LinearLayout empty=skin.card(); empty.addView(skin.text("正在寻找 Mac…",16,skin.text)); skin.gap(empty,7);
            empty.addView(skin.text("在同一 Wi-Fi 下，打开 Mac 上的 MiDrop。",13,skin.muted)); devices.addView(empty); return;
        }
        for (Discovery.Device d:list) {
            boolean known=saved && (!d.id.isEmpty()?d.id.equals(savedId):d.address.equals(savedAddress));
            LinearLayout row=new LinearLayout(this); row.setPadding(skin.dp(18),skin.dp(18),skin.dp(18),skin.dp(18)); row.setGravity(Gravity.CENTER_VERTICAL); row.setBackground(skin.shape(skin.card,22));
            ImageView icon=new ImageView(this); icon.setImageResource(R.drawable.ic_computer); icon.setImageTintList(android.content.res.ColorStateList.valueOf(skin.text)); row.addView(icon,new LinearLayout.LayoutParams(skin.dp(32),skin.dp(32)));
            LinearLayout words=skin.column(); LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1); wp.leftMargin=skin.dp(14); row.addView(words,wp);
            TextView name=skin.text(d.name,16,skin.text); name.setTypeface(null,Typeface.BOLD); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END); words.addView(name); skin.gap(words,6);
            words.addView(skin.text(known?(connected?(hasFiles()?"已信任 · 点击发送":"已连接，可以接收文件"):"已信任 · 正在连接"):"点击连接，在 Mac 上允许",12,skin.muted));
            TextView dot=skin.text(known && connected?"●":"›",known && connected?12:25,known && connected?skin.mint:skin.muted); row.addView(dot);
            row.setOnClickListener(v -> {
                if (busy()) { toast("请等待当前操作完成"); return; }
                if (known) {
                    Protocol.prefs(this).edit().putString("address",d.address).putString("name",d.name).apply();
                    SessionService.start(this);
                    if (hasFiles()) sendFiles(); else toast("已选择 "+d.name);
                } else connect(d);
            });
            devices.addView(row); skin.gap(devices,10);
        }
    }
    private void refreshProgress() {
        String state=Protocol.prefs(this).getString("transfer_state","");
        String msg=Protocol.prefs(this).getString("status","");
        if (state.isEmpty()) { progressArea.setVisibility(View.GONE); return; }
        progressArea.setVisibility(View.VISIBLE);
        String name=Protocol.prefs(this).getString("transfer_name","");
        boolean active=state.equals("preparing") || state.equals("transferring");
        String direction=Protocol.prefs(this).getString("transfer_direction","");
        progressTitle.setText(state.equals("completed")?"传递完成":state.equals("preparing")?"正在准备":state.equals("transferring")?(direction.equals("incoming")?"正在接收":"正在发送"):state.equals("cancelled")?"已取消":"传输未完成");
        progressDetail.setText(active?name:msg);
        long bytes=Protocol.prefs(this).getLong("transfer_bytes",0),size=Protocol.prefs(this).getLong("transfer_size",0);
        progress.setVisibility(active?View.VISIBLE:View.GONE); progress.setIndeterminate(state.equals("preparing"));
        if (size>0) progress.setProgress((int)Math.min(100,bytes*100/size));
        cancel.setVisibility(active?View.VISIBLE:View.GONE);
    }
    private void showOffer() {
        if (offerDialog!=null && offerDialog.isShowing()) {
            if (!SessionService.PENDING.containsKey(shownOffer)) offerDialog.dismiss(); else return;
        }
        if (SessionService.RECEIVING.get()) return;
        for (Map.Entry<String,JSONObject> item:SessionService.PENDING.entrySet()) {
            JSONObject offer=item.getValue(); if (SessionService.DECIDING.contains(item.getKey())) continue; if (offer.optDouble("expires")*1000<System.currentTimeMillis()) continue;
            shownOffer=item.getKey();
            offerDialog=cardDialog(Protocol.prefs(this).getString("name","Mac")+" 想发送文件",
                offer.optString("name")+"\n"+Protocol.size(offer.optLong("size"))+" · 保存到 Download/MiDrop",null,
                "接收",() -> SessionService.decide(this,item.getKey(),true),"拒绝",() -> SessionService.decide(this,item.getKey(),false));
            offerDialog.setCancelable(false); return;
        }
    }
    private void connect(Discovery.Device device) {
        connecting=true; refreshDevices(true); stopService(new Intent(this,SessionService.class));
        AtomicBoolean cancelled=new AtomicBoolean(false);
        ProgressBar spinner=new ProgressBar(this);
        Dialog waiting=cardDialog("连接你的 Mac","请在 "+device.name+" 上点击\n「允许并记住」",spinner,null,null,"取消",() -> cancelled.set(true));
        waiting.setOnCancelListener(d -> cancelled.set(true));
        worker.execute(() -> {
            try {
                String[] observed=new String[1]; HttpsURLConnection probe=Protocol.connect(device.address,"/health",null,observed);
                JSONObject health;
                try { health=Protocol.response(probe); } finally { probe.disconnect(); }
                if (health.optInt("api")<2) throw new java.io.IOException("请先将 Mac 端更新至 MiDrop 0.2");
                String pin=observed[0];
                if (pin==null) throw new java.io.IOException("未能读取 Mac 身份");
                HttpsURLConnection request=Protocol.connect(device.address,"/trust/request",pin,null);
                JSONObject ticket;
                try { ticket=Protocol.post(request,new JSONObject().put("name",Protocol.deviceName(this)).put("client_id",Protocol.clientId(this))); } finally { request.disconnect(); }
                long deadline=SystemClock.elapsedRealtime()+60000;
                while (!cancelled.get() && SystemClock.elapsedRealtime()<deadline) {
                    HttpsURLConnection check=Protocol.connect(device.address,"/trust/status",pin,null); JSONObject result;
                    try { result=Protocol.post(check,ticket); } finally { check.disconnect(); }
                    String state=result.getString("state");
                    if (state.equals("approved")) {
                        if (cancelled.get()) break;
                        Protocol.prefs(this).edit().putString("address",device.address).putString("pin",pin).putString("token",result.getString("token"))
                            .putString("server_id",result.getString("server_id")).putString("name",device.name).putBoolean("connected",false).apply();
                        main.post(() -> { connecting=false; waiting.dismiss(); refreshDevices(true); permissions(); SessionService.start(this); if (hasFiles()) sendFiles(); }); return;
                    }
                    if (state.equals("rejected") || state.equals("expired")) throw new java.io.IOException(state.equals("rejected")?"Mac 已拒绝连接":"连接请求已过期，请重试");
                    Thread.sleep(1200);
                }
                if (!cancelled.get()) throw new java.io.IOException("连接超时，请重试");
            } catch (Exception error) { if (!cancelled.get()) main.post(() -> error(error)); }
            main.post(() -> { connecting=false; waiting.dismiss(); refreshDevices(true); SessionService.start(this); });
        });
    }
    private Dialog cardDialog(String title,String message,View extra,String positive,Runnable yes,String negative,Runnable no) {
        Dialog dialog=new Dialog(this); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout wrapper=skin.column(); wrapper.setPadding(skin.dp(22),skin.dp(16),skin.dp(22),skin.dp(16));
        LinearLayout card=skin.card(); wrapper.addView(card);
        TextView heading=skin.text(title,20,skin.text); heading.setTypeface(null,Typeface.BOLD); card.addView(heading); skin.gap(card,14);
        TextView body=skin.text(message,14,skin.muted); body.setLineSpacing(skin.dp(4),1); card.addView(body);
        if (extra!=null) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(skin.dp(32),skin.dp(32)); p.gravity=Gravity.CENTER_HORIZONTAL; p.topMargin=skin.dp(20); card.addView(extra,p); }
        LinearLayout actions=new LinearLayout(this); skin.gap(card,20); card.addView(actions);
        if (negative!=null) { Button b=skin.button(negative,false); actions.addView(b,new LinearLayout.LayoutParams(0,skin.dp(48),1)); b.setOnClickListener(v -> { dialog.dismiss(); if (no!=null) no.run(); }); }
        if (positive!=null) { Button b=skin.button(positive,true); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,skin.dp(48),1); p.leftMargin=skin.dp(10); actions.addView(b,p); b.setOnClickListener(v -> { dialog.dismiss(); if (yes!=null) yes.run(); }); }
        dialog.setContentView(wrapper); dialog.setCanceledOnTouchOutside(false); dialog.show();
        Window window=dialog.getWindow(); if (window!=null) { window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)); window.setLayout(-1,-2); }
        return dialog;
    }
    private void permissions() {
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},31);
        else if (Build.VERSION.SDK_INT<=28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},32);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] result) { super.onRequestPermissionsResult(request,permissions,result); SessionService.visibilityChanged(); }
    private void sendFiles() {
        if (!hasFiles() || TransferService.ACTIVE.get()) return;
        Intent i=new Intent(getIntent()); i.setClass(this,TransferService.class).setAction("SEND_FILES");
        ArrayList<Uri> uris=TransferService.uris(i);
        if (!uris.isEmpty()) { ClipData grants=ClipData.newRawUri("MiDrop",uris.get(0)); for (int n=1;n<uris.size();n++) grants.addItem(new ClipData.Item(uris.get(n))); i.setClipData(grants); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        startForegroundService(i);
    }
    private void settings() {
        LinearLayout content=skin.column(); content.setPadding(skin.dp(24),skin.dp(8),skin.dp(24),skin.dp(12));
        Switch background=new Switch(this); background.setText("后台接收"); background.setTextColor(skin.text); background.setChecked(Protocol.prefs(this).getBoolean("background_receive",true));
        background.setOnCheckedChangeListener((b,checked) -> { Protocol.prefs(this).edit().putBoolean("background_receive",checked).apply(); if (checked) permissions(); SessionService.start(this); SessionService.visibilityChanged(); });
        content.addView(background); skin.gap(content,16);
        content.addView(skin.text("后台请求会通过通知询问，不会自动接收。",13,skin.muted));
        Button notification=skin.button("通知与后台运行权限",false); notification.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))); content.addView(notification);
        Button folder=skin.button("打开最近收到的文件",false); folder.setOnClickListener(v -> openLast()); content.addView(folder);
        Button manual=skin.button("手动连接",false); manual.setOnClickListener(v -> {
            EditText input=new EditText(this); input.setSingleLine(); input.setHint("192.168.1.8:12345"); input.setText(Protocol.prefs(this).getString("address",""));
            new AlertDialog.Builder(this).setTitle("Mac 地址").setView(input).setNegativeButton("取消",null).setPositiveButton("连接",(d,w) -> {
                try {
                    String address=input.getText().toString().trim(); Protocol.endpoint(address);
                    if (!Protocol.prefs(this).getString("token","").isEmpty()) { Protocol.prefs(this).edit().putString("address",address).apply(); SessionService.start(this); SessionService.visibilityChanged(); }
                    else connect(new Discovery.Device("你的 Mac",address,""));
                } catch (Exception e) { error(e); }
            }).show();
        }); content.addView(manual);
        Button identity=skin.button("连接详情",false); identity.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("连接详情")
            .setMessage(Protocol.prefs(this).getString("name","尚未连接")+"\n"+Protocol.prefs(this).getString("address","")+"\n\n安全指纹\n"+Protocol.prefs(this).getString("pin","")
                +"\n\n"+Protocol.prefs(this).getString("connection_error","")).setPositiveButton("完成",null).show()); content.addView(identity);
        Button forget=skin.button("重新建立信任",false); forget.setOnClickListener(v -> {
            if (busy()) { toast("请先结束当前传输"); return; }
            new AlertDialog.Builder(this).setTitle("重新建立信任？").setMessage("之后请再次选择 Mac，并在 Mac 上允许连接。")
                .setNegativeButton("取消",null).setPositiveButton("重新连接",(d,w) -> {
                    stopService(new Intent(this,SessionService.class)); Protocol.prefs(this).edit().remove("token").remove("pin").remove("server_id").putBoolean("connected",false).apply(); refreshDevices(true);
                }).show();
        }); content.addView(forget); skin.gap(content,16); content.addView(skin.text("MiDrop 0.2.1 · 局域网加密传输",12,skin.muted));
        ScrollView scroll=new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("设置").setView(scroll).setPositiveButton("完成",null).show();
    }
    private void openLast() {
        String saved=Protocol.prefs(this).getString("last_received_uri","");
        if (saved.isEmpty()) { toast("还没有收到文件"); return; }
        Uri uri=Uri.parse(saved); if ("file".equals(uri.getScheme())) uri=Uri.parse("content://"+getPackageName()+".received/file/"+Uri.encode(uri.getLastPathSegment()));
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,Protocol.prefs(this).getString("last_received_mime","*/*")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (Exception e) { toast("请在文件管理器的 Download/MiDrop 中查看"); }
    }
    private void error(Exception e) { if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle("暂时未能连接").setMessage(e.getMessage()==null?e.toString():e.getMessage()).setPositiveButton("知道了",null).show(); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }
}
