package com.remotemate;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

/** The remote as a normal window — for the Chromebook (or a pop-up window on the phone). */
public class RemoteActivity extends Activity implements TvDriver.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private volatile boolean typing;
    private Bridge bridge;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setBackgroundColor(Color.parseColor("#121314"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#121314"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setTextZoom(100);
        ws.setSupportZoom(false);
        web.setVerticalScrollBarEnabled(false);
        bridge = new Bridge();
        web.addJavascriptInterface(bridge, "Remote");
        web.loadUrl("file:///android_asset/panel.html");
        setContentView(web);
        Insets.pad(web);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TvDriver c = Tvs.driver(this);
        c.setListener(this);
        js("window.reloadLayout&&reloadLayout()");
        if (c.host() == null) onState("nohost", "");
        else { onState(c.state(), ""); c.connect(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        bridge.voice.stop();
        Tvs.driver(this).setListener(RemoteService.listener);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bridge.voice.destroy();
        if (!RemoteService.running) Tvs.driver(this).disconnect();
    }

    private void js(String code) { main.post(() -> { if (web != null) web.evaluateJavascript(code, null); }); }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    @Override public void onState(String s, String d) { js("onState(" + q(s) + "," + q(d) + ")"); }
    @Override public void onVolume(int l, int m, boolean mu) { js("onVolume(" + l + "," + m + "," + mu + ")"); }
    @Override public void onTextField(boolean show, String label) { js("onText(" + q(label) + ")"); }
    @Override public void onPower(boolean on) { }

    /** Keyboard as a remote: arrows, Enter = OK, Esc/Backspace = Back, Space = play/pause, +/- volume, M mute, H home. */
    static Button keyFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: return Button.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Button.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Button.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Button.RIGHT;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_DPAD_CENTER: return Button.OK;
            case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_DEL: return Button.BACK;
            case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: return Button.PLAY_PAUSE;
            case KeyEvent.KEYCODE_EQUALS: case KeyEvent.KEYCODE_PLUS: case KeyEvent.KEYCODE_NUMPAD_ADD: case KeyEvent.KEYCODE_VOLUME_UP: return Button.VOL_UP;
            case KeyEvent.KEYCODE_MINUS: case KeyEvent.KEYCODE_NUMPAD_SUBTRACT: case KeyEvent.KEYCODE_VOLUME_DOWN: return Button.VOL_DOWN;
            case KeyEvent.KEYCODE_M: case KeyEvent.KEYCODE_VOLUME_MUTE: return Button.MUTE;
            case KeyEvent.KEYCODE_H: case KeyEvent.KEYCODE_MOVE_HOME: return Button.HOME;
            case KeyEvent.KEYCODE_PAGE_UP: return Button.CH_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return Button.CH_DOWN;
            case KeyEvent.KEYCODE_COMMA: return Button.REWIND;
            case KeyEvent.KEYCODE_PERIOD: return Button.FFWD;
            default: return null;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!typing) {
            Button k = keyFor(e.getKeyCode());
            if (k != null) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) Tvs.driver(this).press(k, Button.SHORT);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private class Bridge extends PanelBridge {
        Bridge() { super(RemoteActivity.this); }
        @Override void js(String code) { RemoteActivity.this.js(code); }
        @JavascriptInterface public String mode() { return "window"; }
        @JavascriptInterface public void haptic() { main.post(() -> web.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)); }
        @JavascriptInterface public void voice(boolean on) {
            if (!on) { voice.stop(); return; }
            main.post(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 3);
                    js("onVoice('noperm')");
                    return;
                }
                startVoice();
            });
        }
        @JavascriptInterface public void hide() { main.post(RemoteActivity.this::finish); }
        @JavascriptInterface public void focusable(boolean f) { typing = f; }
        @JavascriptInterface public String appIcon(String pkg) { return ""; }
        @JavascriptInterface public void setup() {
            main.post(() -> startActivity(new Intent(RemoteActivity.this, MainActivity.class).putExtra("setup", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        }
    }
}
