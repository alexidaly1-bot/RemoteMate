package com.remotemate;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Searches the Wi-Fi for every kind of TV at once:
 * mDNS for Google TV (_androidtvremote2._tcp) and Fire TV (_amzn-wplay._tcp), SSDP for Roku, Samsung, LG and others.
 */
final class Finder {
    interface Callback {
        void found(Ssdp.Tv tv);
        void error(String message);
    }

    private static final String[][] MDNS = {
            {GoogleTvDriver.MDNS_TYPE, "googletv"},
            {"_amzn-wplay._tcp", "firetv"},
    };

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private NsdManager nsd;
    private final List<NsdManager.DiscoveryListener> listeners = new ArrayList<>();
    private WifiManager.MulticastLock lock;
    private final ArrayDeque<Object[]> toResolve = new ArrayDeque<>(); // {NsdServiceInfo, brand}
    private boolean resolving;
    private Ssdp ssdp;
    private Callback cb;

    Finder(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    void start(Callback cb) {
        stop();
        this.cb = cb;
        nsd = ctx.getSystemService(NsdManager.class);
        WifiManager wifi = ctx.getSystemService(WifiManager.class);
        lock = wifi.createMulticastLock("remotemate"); // Samsung phones miss mDNS replies without this
        lock.setReferenceCounted(false);
        lock.acquire();
        for (String[] m : MDNS) {
            final String brand = m[1];
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int e) {
                    if (brand.equals("googletv")) post(() -> { if (Finder.this.cb != null) Finder.this.cb.error("Couldn’t search the Wi-Fi. Type the TV’s IP address instead."); });
                }
                @Override public void onStopDiscoveryFailed(String t, int e) { }
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onServiceFound(NsdServiceInfo s) {
                    synchronized (toResolve) { toResolve.add(new Object[]{s, brand}); }
                    post(Finder.this::resolveNext);
                }
                @Override public void onServiceLost(NsdServiceInfo s) { }
            };
            try {
                nsd.discoverServices(m[0], NsdManager.PROTOCOL_DNS_SD, l);
                listeners.add(l);
            } catch (Exception ignored) { }
        }
        final Ssdp s = new Ssdp();
        ssdp = s;
        new Thread(() -> s.search(8000, tv -> post(() -> { if (Finder.this.cb != null) Finder.this.cb.found(tv); })), "ssdp").start();
    }

    private void post(Runnable r) { main.post(r); }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || nsd == null) return;
        Object[] next;
        synchronized (toResolve) { next = toResolve.poll(); }
        if (next == null) return;
        resolving = true;
        final String brand = (String) next[1];
        nsd.resolveService((NsdServiceInfo) next[0], new NsdManager.ResolveListener() {
            @Override public void onResolveFailed(NsdServiceInfo i, int e) { resolving = false; post(Finder.this::resolveNext); }
            @Override public void onServiceResolved(NsdServiceInfo i) {
                resolving = false;
                InetAddress a = i.getHost();
                if (a != null) {
                    Ssdp.Tv tv = new Ssdp.Tv(a.getHostAddress(), brand, i.getServiceName(), "");
                    post(() -> { if (cb != null) cb.found(tv); });
                }
                post(Finder.this::resolveNext);
            }
        });
    }

    void stop() {
        cb = null;
        if (nsd != null) for (NsdManager.DiscoveryListener l : listeners) try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) { }
        listeners.clear();
        synchronized (toResolve) { toResolve.clear(); }
        resolving = false;
        if (ssdp != null) { ssdp.stop(); ssdp = null; }
        if (lock != null && lock.isHeld()) lock.release();
    }
}
