package com.remotemate;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.view.Display;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/** Keeps the edge handle on screen and slides the remote panel in and out. */
public class RemoteService extends Service implements TvDriver.Listener {
    public static final String SHOW = "show", HIDE = "hide", TOGGLE = "toggle", STOP = "stop", RELOAD = "reload";
    static volatile boolean running;
    static volatile TvDriver.Listener listener;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context wctx;
    private WindowManager wm;
    private SharedPreferences prefs;

    private View handle;
    private WindowManager.LayoutParams handleLp;
    private FrameLayout panel;
    private WebView web;
    private WindowManager.LayoutParams panelLp;
    private boolean panelAdded, shown, animating;
    private final Runnable idleDisconnect = () -> { if (!shown) Tvs.driver(this).disconnect(); };

    private Bridge bridge;
    private final Map<String, String> iconCache = new HashMap<>();

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        prefs = Tvs.prefs(this);
        Display d = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        wctx = createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        wm = wctx.getSystemService(WindowManager.class);
        wctx.registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration c) { main.postDelayed(RemoteService.this::layoutAll, 120); }
            @Override public void onLowMemory() { }
        });
        goForeground();
        TvDriver c = Tvs.driver(this);
        c.setListener(this);
        listener = this;
        buildHandle();
        buildPanel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
        if (RELOAD.equals(a)) {
            TvDriver c = Tvs.driver(this);
            c.setListener(this);
            js("window.reloadLayout&&reloadLayout()");
            pushState();
            if (shown) c.connect();
        }
        if (SHOW.equals(a)) show();
        else if (HIDE.equals(a)) hide();
        else if (TOGGLE.equals(a)) { if (shown) hide(); else show(); }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        listener = null;
        if (bridge != null) bridge.voice.destroy();
        main.removeCallbacksAndMessages(null);
        try { if (handle != null) wm.removeView(handle); } catch (Exception ignored) { }
        try { if (panelAdded) wm.removeView(panel); } catch (Exception ignored) { }
        if (web != null) web.destroy();
        TvDriver c = Tvs.driver(this);
        c.setListener(null);
        c.disconnect();
        super.onDestroy();
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("remote", "Remote", NotificationManager.IMPORTANCE_MIN));
        PendingIntent toggle = PendingIntent.getService(this, 1, new Intent(this, RemoteService.class).setAction(TOGGLE), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, RemoteService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "remote")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("RemoteMate is ready")
                .setContentText("Tap to show or hide it. Or swipe the bar on the right edge.")
                .setContentIntent(toggle)
                .addAction(new Notification.Action.Builder(null, "Turn off", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT < 34) { startForeground(7, n); return; } // Android 13 has no "special use" type
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            startForeground(7, n, mic ? type | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : type);
        } catch (Exception e) {
            startForeground(7, n, type);
        }
    }

    // ================= sizes =================

    private Rect screen() { return wm.getMaximumWindowMetrics().getBounds(); }

    /** Remote size in real pixels. Measured once on the big (unfolded) screen and kept the same when folded. */
    private int[] panelSize() {
        Rect s = screen();
        int w = prefs.getInt("pw", 0), h = prefs.getInt("ph", 0);
        if (w == 0 && s.width() >= 1600) {
            // matches the mockup: 26.2% of the unfolded width, 87% of its height
            w = Math.round(s.width() * 0.262f);
            h = Math.round(s.height() * 0.8715f);
            prefs.edit().putInt("pw", w).putInt("ph", h).apply();
        }
        if (w == 0) { w = 486; h = 1884; }
        h = Math.min(h, s.height() - 60);
        w = Math.min(w, s.width() - 24);
        return new int[]{w, h};
    }

    // ================= edge handle =================

    @SuppressLint("ClickableViewAccessibility")
    private void buildHandle() {
        FrameLayout f = new FrameLayout(wctx);
        View bar = new View(wctx);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.argb(150, 235, 235, 235));
        g.setStroke(2, Color.argb(90, 0, 0, 0));
        g.setCornerRadius(20);
        bar.setBackground(g);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(14, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        blp.setMargins(0, 22, 6, 22);
        f.addView(bar, blp);
        handle = f;
        handleLp = new WindowManager.LayoutParams(46, 300, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        handleLp.gravity = Gravity.TOP | Gravity.START;
        handleLp.setTitle("RemoteMate handle");
        f.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; long t0; boolean opened, dragging; int startY;
            final Runnable startDrag = () -> { dragging = true; handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); bar.setAlpha(1f); };
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); t0 = e.getEventTime(); opened = false; dragging = false; startY = handleLp.y;
                        main.postDelayed(startDrag, 500);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (dragging) {
                            handleLp.y = clamp(startY + (int) dy, 0, screen().height() - handleLp.height);
                            wm.updateViewLayout(handle, handleLp);
                        } else if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            main.removeCallbacks(startDrag);
                            if (!opened && dx < -24 && Math.abs(dx) > Math.abs(dy)) { opened = true; show(); }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(startDrag);
                        if (dragging) {
                            prefs.edit().putFloat("handleY", handleLp.y / (float) screen().height()).apply();
                            bar.setAlpha(1f);
                        } else if (!opened && e.getActionMasked() == MotionEvent.ACTION_UP && e.getEventTime() - t0 < 400
                                && Math.abs(e.getRawX() - sx) < 12 && Math.abs(e.getRawY() - sy) < 12) {
                            if (shown) hide(); else show();
                        }
                        return true;
                }
                return false;
            }
        });
        placeHandle();
        wm.addView(handle, handleLp);
    }

    private void placeHandle() {
        Rect s = screen();
        handleLp.x = s.width() - handleLp.width;
        handleLp.y = clamp(Math.round(prefs.getFloat("handleY", 0.58f) * s.height()), 0, s.height() - handleLp.height);
    }

    // ================= panel =================

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildPanel() {
        panel = new FrameLayout(wctx);
        panel.setClipChildren(true);
        web = new WebView(wctx);
        web.setBackgroundColor(Color.TRANSPARENT);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setTextZoom(100);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        bridge = new Bridge();
        web.addJavascriptInterface(bridge, "Remote");
        web.loadUrl("file:///android_asset/panel.html");
        panel.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        panelLp = new WindowManager.LayoutParams(10, 10, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        panelLp.setTitle("RemoteMate");
        placePanel();
    }

    private void placePanel() {
        Rect s = screen();
        int[] sz = panelSize();
        panelLp.width = sz[0];
        panelLp.height = sz[1];
        int margin = Math.round(sz[0] * 0.247f); // same gap from the right edge as the mockup
        int x = s.width() - sz[0] - margin;
        if (x < 16) x = Math.max(8, s.width() - sz[0] - 16);
        panelLp.x = x;
        panelLp.y = Math.max(0, Math.round((s.height() - sz[1]) * 0.67f));
    }

    private void layoutAll() {
        if (handle == null) return;
        placeHandle();
        try { wm.updateViewLayout(handle, handleLp); } catch (Exception ignored) { }
        placePanel();
        if (panelAdded) try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
    }

    void show() {
        main.removeCallbacks(idleDisconnect);
        if (shown && !animating) return;
        shown = true;
        placePanel();
        if (!panelAdded) { wm.addView(panel, panelLp); panelAdded = true; }
        web.evaluateJavascript("window.onShown&&onShown()", null);
        pushState();
        panel.setTranslationX(panelLp.width + 40);
        animating = true;
        panel.animate().translationX(0).setDuration(230).setInterpolator(new DecelerateInterpolator(1.6f))
                .setListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator a) { animating = false; } }).start();
        TvDriver c = Tvs.driver(this);
        if (c.host() == null) onState("nohost", "");
        else c.connect();
    }

    void hide() {
        if (!shown) return;
        shown = false;
        if (bridge != null) bridge.voice.stop();
        setFocusable(false);
        animating = true;
        panel.animate().translationX(panelLp.width + 40).setDuration(190).setInterpolator(new DecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        animating = false;
                        if (!shown && panelAdded) { try { wm.removeView(panel); } catch (Exception ignored) { } panelAdded = false; }
                    }
                }).start();
        main.postDelayed(idleDisconnect, 90_000);
    }

    private void setFocusable(boolean f) {
        int flags = panelLp.flags;
        panelLp.flags = f ? flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE : flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        panelLp.softInputMode = f ? WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        if (panelAdded && flags != panelLp.flags) try { wm.updateViewLayout(panel, panelLp); } catch (Exception ignored) { }
        if (f) web.requestFocus();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private void js(String code) { main.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void pushState() {
        TvDriver c = Tvs.driver(this);
        js("onState(" + q(c.host() == null ? "nohost" : c.state()) + ",'')");
    }

    // ================= TV events =================

    @Override public void onState(String state, String detail) { js("onState(" + q(state) + "," + q(detail) + ")"); }

    @Override public void onVolume(int level, int max, boolean muted) { js("onVolume(" + level + "," + max + "," + muted + ")"); }

    @Override public void onTextField(boolean show, String label) { if (shown) js("onText(" + q(label) + ")"); }

    @Override public void onPower(boolean on) { }

    // ================= bridge for the panel page =================

    private class Bridge extends PanelBridge {
        Bridge() { super(RemoteService.this); }
        @Override void js(String code) { RemoteService.this.js(code); }
        @JavascriptInterface public void haptic() { main.post(() -> panel.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)); }
        @JavascriptInterface public void voice(boolean on) {
            if (!on) { voice.stop(); return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { js("onVoice('noperm')"); return; }
            startVoice();
        }
        @JavascriptInterface public void hide() { main.post(RemoteService.this::hide); }
        @JavascriptInterface public void focusable(boolean f) { main.post(() -> setFocusable(f)); }
        @JavascriptInterface public void setup() {
            main.post(() -> {
                RemoteService.this.hide();
                startActivity(new Intent(RemoteService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            });
        }
        @JavascriptInterface public String appIcon(String pkg) {
            synchronized (iconCache) {
                if (iconCache.containsKey(pkg)) return iconCache.get(pkg);
                String out = "";
                try {
                    Drawable d = getPackageManager().getApplicationIcon(pkg);
                    Bitmap bm = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                    Canvas cv = new Canvas(bm);
                    d.setBounds(0, 0, 96, 96);
                    d.draw(cv);
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
                    out = "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                } catch (Exception ignored) { }
                iconCache.put(pkg, out);
                return out;
            }
        }
    }
}
