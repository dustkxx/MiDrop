package dev.midrop;

import android.content.Context;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.*;
import java.net.*;
import java.util.*;

/** One app-wide NSD resolver; UI and background session share it. */
final class Discovery {
    interface Listener { void changed(List<Device> devices); }
    static final class Device {
        final String name,address,id;
        Device(String n,String a,String i) { name=n; address=a; id=i; }
    }
    private static Discovery instance;
    static synchronized Discovery get(Context c) { if (instance==null) instance=new Discovery(c.getApplicationContext()); return instance; }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Listener> listeners=new ArrayList<>();
    private final Map<String,Device> devices=new LinkedHashMap<>();
    private final Queue<NsdServiceInfo> queue=new ArrayDeque<>();
    private NsdManager.DiscoveryListener discovery;
    private WifiManager.MulticastLock multicast;
    private NsdManager nsd;
    private boolean resolving;
    private int generation;
    private Discovery(Context c) { context=c; }
    void add(Listener l) { if (!listeners.contains(l)) listeners.add(l); if (discovery==null) start(); l.changed(new ArrayList<>(devices.values())); }
    void remove(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) {
            generation++;
            if (discovery!=null) try { nsd.stopServiceDiscovery(discovery); } catch (Exception ignored) {}
            discovery=null; resolving=false; queue.clear(); devices.clear();
            if (multicast!=null && multicast.isHeld()) multicast.release();
        }
    }
    private void emit() { for (Listener l:new ArrayList<>(listeners)) l.changed(new ArrayList<>(devices.values())); }
    private void start() {
        try {
            WifiManager wifi=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            multicast=wifi.createMulticastLock("MiDrop:discovery"); multicast.setReferenceCounted(false); multicast.acquire();
            nsd=(NsdManager)context.getSystemService(Context.NSD_SERVICE);
            discovery=new NsdManager.DiscoveryListener() {
                public void onDiscoveryStarted(String t) {}
                public void onDiscoveryStopped(String t) {}
                public void onStartDiscoveryFailed(String t,int e) {}
                public void onStopDiscoveryFailed(String t,int e) {}
                public void onServiceFound(NsdServiceInfo s) { main.post(() -> { queue.add(s); next(); }); }
                public void onServiceLost(NsdServiceInfo s) { main.post(() -> { devices.remove(s.getServiceName()); emit(); }); }
            };
            nsd.discoverServices("_midrop._tcp.",NsdManager.PROTOCOL_DNS_SD,discovery);
        } catch (Exception ignored) {}
    }
    @SuppressWarnings("deprecation") private void next() {
        if (resolving || queue.isEmpty() || discovery==null) return;
        resolving=true; final int epoch=generation;
        NsdServiceInfo pending=queue.remove();
        nsd.resolveService(pending,new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo s,int e) { main.post(() -> { if (epoch!=generation) return; resolving=false; next(); }); }
            public void onServiceResolved(NsdServiceInfo s) { main.post(() -> {
                if (epoch!=generation) return;
                resolving=false;
                InetAddress host=s.getHost();
                if (Build.VERSION.SDK_INT>=34) for (InetAddress candidate:s.getHostAddresses()) if (candidate instanceof Inet4Address) { host=candidate; break; }
                if (host instanceof Inet4Address) {
                    byte[] value=s.getAttributes().get("id");
                    String id=value==null?"":new String(value,java.nio.charset.StandardCharsets.UTF_8);
                    devices.put(s.getServiceName(),new Device(s.getServiceName(),host.getHostAddress()+":"+s.getPort(),id)); emit();
                }
                next();
            }); }
        });
    }
}
