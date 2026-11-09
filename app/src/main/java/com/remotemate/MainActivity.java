package com.remotemate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

/** Setup: find TVs on the Wi-Fi, pair the way each brand allows, photo fallback, permissions. */
public class MainActivity extends Activity {
    private static final int REQ_CAMERA = 10, REQ_PICK = 11;

    private WebView web;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Finder finder;
    private TvDriver pending;
    private String pendingName = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Tvs.prefs(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#17191a"));
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setTextZoom(100);
        web.addJavascriptInterface(new Setup(), "Setup");
        web.loadUrl("file:///android_asset/setup.html");
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { if (url.endsWith("setup.html")) licencesShown(false); }
        });
        setContentView(web);
        Insets.pad(web);
        finder = new Finder(this);
        // On a Chromebook, open straight into the remote window once a TV is set up
        if (isChromebook() && Tvs.current(this) != null && !getIntent().getBooleanExtra("setup", false)) { openWindow(); finish(); }
    }

    boolean isChromebook() {
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature("org.chromium.arc") || pm.hasSystemFeature("org.chromium.arc.device_management");
    }

    void openWindow() { startActivity(new Intent(this, RemoteActivity.class)); }

    @Override
    protected void onResume() {
        super.onResume();
        js("refresh()");
        if (!isChromebook() && Settings.canDrawOverlays(this) && Tvs.current(this) != null) startRemote(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        finder.stop();
        js("paused()");
    }

    private void js(String code) { main.post(() -> web.evaluateJavascript("window." + code.replaceFirst("\\(.*", "") + "&&" + code, null)); }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    private void startRemote(String action) {
        Intent i = new Intent(this, RemoteService.class);
        if (action != null) i.setAction(action);
        startForegroundService(i);
    }

    private static String tvJson(Ssdp.Tv t) {
        try {
            return new JSONObject().put("host", t.host).put("brand", t.brand).put("name", t.name).put("model", t.model)
                    .put("brandName", BrandDetector.niceOs(t.brand)).put("supported", BrandDetector.supported(t.brand)).toString();
        } catch (Exception e) { return "{}"; }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        js("refresh()");
        if (RemoteService.running && Settings.canDrawOverlays(this)) {
            // restart so the remote can use the microphone
            stopService(new Intent(this, RemoteService.class));
            main.postDelayed(() -> startRemote(null), 300);
        }
    }

    // ================= photo of the TV's About screen =================

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK) { js("onPhoto(null,'')"); return; }
        Uri uri = req == REQ_CAMERA ? PhotoProvider.uri(this) : data == null ? null : data.getData();
        if (uri == null) { js("onPhoto(null,'')"); return; }
        js("onPhoto('reading','')");
        try {
            InputImage img = InputImage.fromFilePath(this, uri);
            TextRecognizer r = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            r.process(img)
                    .addOnSuccessListener(t -> { r.close(); photoText(t.getText()); })
                    .addOnFailureListener(e -> { r.close(); js("onPhoto(null," + q("Couldn’t read the photo. " + e.getMessage()) + ")"); });
        } catch (Exception e) {
            js("onPhoto(null," + q("Couldn’t open the photo.") + ")");
        }
    }

    private void photoText(String text) {
        BrandDetector.Result r = BrandDetector.read(text);
        try {
            js("onPhoto(" + new JSONObject().put("platform", r.platform).put("label", r.label).put("ip", r.ip).put("model", r.model)
                    .put("brandName", BrandDetector.niceOs(r.platform)).put("supported", BrandDetector.supported(r.platform)) + ",'')");
        } catch (Exception ignored) { }
    }

    // ================= bridge for setup.html =================

    private class Setup {
        @JavascriptInterface public String status() {
            try {
                JSONObject cur = Tvs.current(MainActivity.this);
                return new JSONObject()
                        .put("overlay", Settings.canDrawOverlays(MainActivity.this))
                        .put("mic", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                        .put("notif", checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                        .put("tvs", Tvs.list(MainActivity.this))
                        .put("current", cur == null ? "" : cur.optString("id"))
                        .put("running", RemoteService.running)
                        .toString();
            } catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void askOverlay() {
            main.post(() -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        }
        @JavascriptInterface public void askMic() { main.post(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1)); }
        @JavascriptInterface public void askNotif() { main.post(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2)); }

        /** Searches for every kind of TV at once. Each one found → onFound(tv). */
        @JavascriptInterface public void find() {
            main.post(() -> finder.start(new Finder.Callback() {
                @Override public void found(Ssdp.Tv tv) { js("onFound(" + tvJson(tv) + ")"); }
                @Override public void error(String m) { js("onFindError(" + q(m) + ")"); }
            }));
        }
        @JavascriptInterface public void stopFind() { main.post(finder::stop); }

        /** Works out what's at a typed-in address → onProbe(tv or null). */
        @JavascriptInterface public void probe(String host) {
            new Thread(() -> {
                Ssdp.Tv tv = Ssdp.probe(host.trim());
                js("onProbe(" + (tv == null ? "null" : tvJson(tv)) + "," + q(host.trim()) + ")");
            }).start();
        }

        /** Starts pairing the fastest way the brand allows. → onPair('code' | 'done' | 'error', msg) */
        @JavascriptInterface public void pair(String brand, String host, String name) {
            main.post(finder::stop);
            new Thread(() -> {
                try {
                    TvDriver d = Tvs.make(MainActivity.this, brand);
                    pending = d;
                    pendingName = name;
                    d.pairStart(host.trim(), Build.MODEL + " (RemoteMate)");
                    if (d.pairing() == TvDriver.Pairing.CODE) js("onPair('code','')");
                    else {
                        String n = d instanceof RokuDriver && !((RokuDriver) d).deviceName().isEmpty() ? ((RokuDriver) d).deviceName() : name;
                        paired(d, n);
                    }
                } catch (Exception e) {
                    js("onPair('error'," + q(friendly(e)) + ")");
                }
            }).start();
        }

        @JavascriptInterface public void code(String code) {
            new Thread(() -> {
                TvDriver d = pending;
                try {
                    if (d == null) throw new Exception("Start pairing again.");
                    d.pairFinish(code);
                    paired(d, pendingName);
                } catch (Exception e) {
                    js("onPair('badcode'," + q(friendly(e)) + ")");
                }
            }).start();
        }

        @JavascriptInterface public void cancelPair() { TvDriver d = pending; if (d != null) d.pairCancel(); pending = null; }

        @JavascriptInterface public void use(String id) {
            Tvs.use(MainActivity.this, id);
            main.post(() -> { if (RemoteService.running) startRemote(RemoteService.RELOAD); js("refresh()"); });
        }
        @JavascriptInterface public void forget(String id) {
            Tvs.forget(MainActivity.this, id);
            main.post(() -> { if (RemoteService.running) startRemote(RemoteService.RELOAD); js("refresh()"); });
        }

        @JavascriptInterface public void photo() {
            main.post(() -> {
                try {
                    Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, PhotoProvider.uri(MainActivity.this))
                            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(i, REQ_CAMERA);
                } catch (Exception e) {
                    js("onPhoto(null,'No camera app found. Choose a photo instead.')");
                }
            });
        }
        @JavascriptInterface public void pickPhoto() {
            main.post(() -> {
                try {
                    startActivityForResult(new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_PICK);
                } catch (Exception e) {
                    startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK);
                }
            });
        }

        @JavascriptInterface public void show() {
            main.post(() -> {
                if (isChromebook()) { openWindow(); return; }
                if (!Settings.canDrawOverlays(MainActivity.this)) { askOverlay(); return; }
                startRemote(RemoteService.SHOW);
                moveTaskToBack(true);
            });
        }
        @JavascriptInterface public void window() { main.post(MainActivity.this::openWindow); }
        @JavascriptInterface public boolean chromebook() { return isChromebook(); }
        @JavascriptInterface public void stop() { main.post(() -> { stopService(new Intent(MainActivity.this, RemoteService.class)); js("refresh()"); }); }
        @JavascriptInterface public void resetSize() {
            prefs.edit().remove("pw").remove("ph").remove("handleY").apply();
            main.post(() -> { if (RemoteService.running) { stopService(new Intent(MainActivity.this, RemoteService.class)); main.postDelayed(() -> startRemote(null), 300); } });
        }
        @JavascriptInterface public void addTile() {
            main.post(() -> {
                StatusBarManager sb = getSystemService(StatusBarManager.class);
                sb.requestAddTileService(new ComponentName(MainActivity.this, RemoteTile.class), "RemoteMate",
                        Icon.createWithResource(MainActivity.this, R.drawable.ic_stat), getMainExecutor(), r -> js("refresh()"));
            });
        }
        @JavascriptInterface public void licences() {
            main.post(() -> { web.loadUrl("file:///android_asset/licences.html"); licencesShown(true); });
        }
    }

    private void paired(TvDriver d, String name) {
        Tvs.add(this, d.brand(), d.host(), name);
        pending = null;
        js("onPair('done','')");
        if (isChromebook()) main.post(this::openWindow);
        else if (Settings.canDrawOverlays(this)) main.post(() -> startRemote(RemoteService.RELOAD));
    }

    /** Back from the licences page returns to setup. */
    private final android.window.OnBackInvokedCallback backFromLicences = () -> web.loadUrl("file:///android_asset/setup.html");

    void licencesShown(boolean shown) {
        if (shown) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backFromLicences);
        else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backFromLicences);
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.NoRouteToHostException)
            return "Couldn’t reach the TV. Check it’s on and on the same Wi-Fi as your phone.";
        if (e instanceof java.net.UnknownHostException) return "That address doesn’t look right.";
        return m;
    }
}
