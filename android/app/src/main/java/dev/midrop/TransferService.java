package dev.midrop;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

public class TransferService extends Service {
    static final AtomicBoolean ACTIVE = new AtomicBoolean(false);
    private volatile boolean cancelled = false;
    private volatile HttpsURLConnection connection;
    private PowerManager.WakeLock wake;
    private long lastProgress;
    private String phase="preparing",currentName="",uploadId="";
    private long payloadSize=0,sentBytes=0;
    private static final int NOTICE = 71;
    private static final long MAX_FILE = 20L * 1024 * 1024 * 1024;

    @SuppressWarnings("deprecation") static ArrayList<Uri> uris(Intent intent) {
        LinkedHashSet<Uri> result = new LinkedHashSet<>();
        if (intent == null) return new ArrayList<>();
        Object streams = intent.getExtras() == null ? null : intent.getExtras().get(Intent.EXTRA_STREAM);
        if (streams instanceof Uri) result.add((Uri)streams);
        if (streams instanceof ArrayList<?>) for (Object item : (ArrayList<?>)streams) if (item instanceof Uri) result.add((Uri)item);
        if (intent.getClipData() != null) for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
            Uri uri = intent.getClipData().getItemAt(i).getUri(); if (uri != null) result.add(uri);
        }
        // Exported share targets accept only content URIs granted by the sending app.
        result.removeIf(uri -> !"content".equals(uri.getScheme()));
        return new ArrayList<>(result);
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel("transfer", "文件传输", NotificationManager.IMPORTANCE_LOW));
        // Files left by a killed process contain private source content; remove them on next start.
        File[] leftovers = getCacheDir().listFiles((dir, name) -> name.startsWith("midrop-"));
        if (leftovers != null) for (File file : leftovers) file.delete();
    }
    private Notification notice(String message, int percent, boolean ongoing) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, "transfer").setSmallIcon(dev.midrop.R.drawable.ic_status)
            .setContentTitle("MiDrop · 发送到 Mac").setContentText(message).setContentIntent(open).setOngoing(ongoing).setOnlyAlertOnce(true);
        if (ongoing) {
            PendingIntent cancel = PendingIntent.getService(this, 1, new Intent(this, TransferService.class).setAction("CANCEL"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "取消", cancel).build());
            builder.setProgress(100, Math.max(percent, 0), percent < 0);
        }
        return builder.build();
    }
    private void report(String message, int percent) {
        Protocol.progress(this,"outgoing",phase,currentName,sentBytes,payloadSize,message);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE, notice(message, percent, true));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("CANCEL".equals(intent.getAction())) {
            cancelled = true;
            final String cancelId=uploadId;
            if (!cancelId.isEmpty()) new Thread(() -> { try { Protocol.post(this,"/transfers/"+cancelId+"/cancel",new JSONObject()); } catch (Exception ignored) {} }).start();
            HttpsURLConnection active = connection;
            if (active != null) active.disconnect();
            if (!ACTIVE.get()) stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTICE, notice("准备发送…", -1, true));
        if (!ACTIVE.compareAndSet(false, true)) return START_NOT_STICKY;
        cancelled = false;
        wake = ((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiDrop:transfer");
        wake.acquire(60 * 60 * 1000L);
        new Thread(() -> transfer(intent), "MiDrop-transfer").start();
        return START_NOT_STICKY;
    }
    private void checkCancelled() throws IOException { if (cancelled) throw new IOException("已取消"); }
    private String filename(Uri uri, int number) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "文件-" + number;
    }
    private void transfer(Intent intent) {
        int completed = 0;
        String result;
        try {
            String target = Protocol.prefs(this).getString("address", "");
            String pin = Protocol.prefs(this).getString("pin", "");
            String token = Protocol.prefs(this).getString("token", "");
            if (pin.length() != 64 || token.isEmpty()) throw new IOException("请先配对 Mac");
            ArrayList<Uri> items = uris(intent);
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            int total = items.size();
            if (total == 0 && text == null) throw new IOException("没有收到可读取的文件");
            if (total == 0) total = 1;
            for (int index = 0; index < total; index++) {
                checkCancelled();
                String name = items.isEmpty() ? "分享文字-" + System.currentTimeMillis() + ".txt" : filename(items.get(index), index + 1);
                String prefix = "(" + (index + 1) + "/" + total + ") ";
                File staged = File.createTempFile("midrop-", ".part", getCacheDir());
                try {
                    currentName=name; phase="preparing"; payloadSize=0; sentBytes=0;
                    report("正在准备 · "+prefix+name,-1);
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    long size = 0;
                    InputStream source = items.isEmpty() ? new ByteArrayInputStream(text.toString().getBytes("UTF-8")) : getContentResolver().openInputStream(items.get(index));
                    if (source == null) throw new IOException("无法读取文件：" + name);
                    try (InputStream input = source; OutputStream output = new FileOutputStream(staged)) {
                        byte[] buffer = new byte[256 * 1024];
                        int n;
                        while ((n = input.read(buffer)) != -1) {
                            checkCancelled(); size += n;
                            if (size > MAX_FILE) throw new IOException("单个文件不能超过 20 GiB");
                            output.write(buffer, 0, n); digest.update(buffer, 0, n);
                        }
                    }
                    checkCancelled();
                    String checksum = Protocol.hex(digest.digest());
                    uploadId=java.util.UUID.randomUUID().toString(); payloadSize=size; phase="transferring";
                    connection = Protocol.connect(target, "/upload?name=" + URLEncoder.encode(name, "UTF-8"), pin, null);
                    connection.setRequestMethod("POST"); connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(size);
                    connection.setRequestProperty("Content-Type", "application/octet-stream");
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                    connection.setRequestProperty("X-Content-SHA256", checksum);
                    connection.setRequestProperty("X-Transfer-ID",uploadId);
                    report(prefix + "发送 " + name + " · 0%", 0);
                    try (InputStream input = new FileInputStream(staged); OutputStream output = connection.getOutputStream()) {
                        byte[] buffer = new byte[256 * 1024]; long sent = 0; int n;
                        while ((n = input.read(buffer)) != -1) {
                            checkCancelled(); output.write(buffer, 0, n); sent += n; sentBytes=sent;
                            long now = SystemClock.elapsedRealtime();
                            if (now - lastProgress > 400) {
                                int percent = size == 0 ? 100 : (int)(100 * sent / size);
                                report(prefix + "发送 " + name + " · " + percent + "%", percent); lastProgress = now;
                            }
                        }
                    }
                    JSONObject ack = Protocol.response(connection);
                    if (!checksum.equals(ack.getString("sha256"))) throw new IOException("Mac 确认的文件校验值不一致");
                    completed++;
                } finally {
                    if (connection != null) { connection.disconnect(); connection = null; }
                    staged.delete();
                }
            }
            result = "已发送 " + completed + " 个文件到 Mac";
        } catch (Exception error) {
            result = (cancelled ? "已取消" : "发送失败：" + (error.getMessage() == null ? error.toString() : error.getMessage())) + " · 已完成 " + completed + " 个";
        } finally {
            if (wake != null && wake.isHeld()) wake.release();
        }
        final String finalResult = result;
        new Handler(Looper.getMainLooper()).post(() -> {
            Protocol.progress(this,"outgoing",cancelled?"cancelled":completedSuccessfully(finalResult)?"completed":"failed",currentName,sentBytes,payloadSize,finalResult);
            stopForeground(STOP_FOREGROUND_REMOVE);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE, notice(finalResult, 100, false));
            ACTIVE.set(false);
            stopSelf();
        });
    }
    private boolean completedSuccessfully(String result) { return result.startsWith("已发送"); }
    @Override public void onDestroy() {
        cancelled = true;
        if (connection != null) connection.disconnect();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
