package dev.midrop;

import android.content.*;
import android.os.Build;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;
import org.json.JSONObject;

final class Protocol {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("midrop", Context.MODE_PRIVATE); }
    static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    static String fingerprint(X509Certificate c) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded())); }
    static String clientId(Context c) {
        String id = prefs(c).getString("client_id", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); prefs(c).edit().putString("client_id", id).apply(); }
        return id;
    }
    static String deviceName(Context c) {
        String name = Settings.Global.getString(c.getContentResolver(), "device_name");
        return name == null || name.trim().isEmpty() ? Build.MANUFACTURER + " " + Build.MODEL : name;
    }
    static String serverId(Context c) {
        String id = prefs(c).getString("server_id", "");
        String pin = prefs(c).getString("pin", "");
        return !id.isEmpty() ? id : pin.length() == 64 ? pin.substring(0,24) : "";
    }
    static String endpoint(String address) throws IOException {
        if (!address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}:[0-9]{1,5}")) throw new IOException("请输入 Mac 设置中的 IP:端口");
        String[] pieces = address.split(":");
        for (String p : pieces[0].split("\\.")) if (Integer.parseInt(p) > 255) throw new IOException("IP 地址无效");
        int port = Integer.parseInt(pieces[1]);
        if (port < 1 || port > 65535) throw new IOException("端口无效");
        return "https://" + address;
    }
    static HttpsURLConnection connect(String address, String path, final String pin, final String[] observed) throws Exception {
        X509TrustManager trust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String t) throws CertificateException { throw new CertificateException(); }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("缺少证书");
                    String actual = fingerprint(chain[0]);
                    if (pin != null && !MessageDigest.isEqual(pin.getBytes("UTF-8"), actual.getBytes("UTF-8"))) throw new CertificateException("Mac 身份发生变化，请在设置中重新连接");
                    if (observed != null) observed[0] = actual;
                } catch (CertificateException e) { throw e; } catch (Exception e) { throw new CertificateException(e); }
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{trust}, new SecureRandom());
        HttpsURLConnection c = (HttpsURLConnection)new URL(endpoint(address) + path).openConnection(java.net.Proxy.NO_PROXY);
        c.setSSLSocketFactory(ssl.getSocketFactory());
        c.setHostnameVerifier((h,s) -> true); // Exact certificate pin supplies identity, not DNS.
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(8000); c.setReadTimeout(45000);
        return c;
    }
    static HttpsURLConnection authenticated(Context context, String path) throws Exception {
        SharedPreferences p = prefs(context);
        String pin = p.getString("pin", ""), token = p.getString("token", "");
        if (pin.length() != 64 || token.isEmpty()) throw new IOException("请先连接 Mac");
        HttpsURLConnection c = connect(p.getString("address", ""), path, pin, null);
        c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }
    static JSONObject post(Context context, String path, JSONObject body) throws Exception {
        HttpsURLConnection c = authenticated(context, path);
        try { return post(c,body); } finally { c.disconnect(); }
    }
    static JSONObject post(HttpsURLConnection c, JSONObject body) throws Exception {
        byte[] bytes = body.toString().getBytes("UTF-8");
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(bytes.length);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        return response(c);
    }
    static JSONObject response(HttpsURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream s = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (s == null) throw new IOException("Mac 暂时不可用（" + code + "）");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (InputStream in = s) {
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) != -1) {
                if (bytes.size()+n > 1024*1024) throw new IOException("响应过大");
                bytes.write(b,0,n);
            }
        }
        JSONObject json = new JSONObject(bytes.toString("UTF-8"));
        if (code < 200 || code >= 300) throw new ApiException(code,json.optString("error","请求失败"));
        return json;
    }
    static class ApiException extends IOException {
        final int code;
        ApiException(int code,String message) { super(message); this.code=code; }
    }
    static void progress(Context c, String direction, String state, String name, long bytes, long size, String message) {
        prefs(c).edit().putString("transfer_direction",direction).putString("transfer_state",state)
            .putString("transfer_name",name).putLong("transfer_bytes",bytes).putLong("transfer_size",size)
            .putString("status",message).apply();
    }
    static String size(long n) {
        if (n < 1024) return n+" B";
        if (n < 1024*1024) return String.format(Locale.ROOT,"%.0f KB",n/1024.0);
        if (n < 1024L*1024*1024) return String.format(Locale.ROOT,"%.1f MB",n/1048576.0);
        return String.format(Locale.ROOT,"%.1f GB",n/1073741824.0);
    }
}
