package com.remotemate;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

/** What the remote page (panel.html) can ask the app to do. Shared by the edge panel and the window. */
abstract class PanelBridge {
    final Context ctx;
    final Voice voice = new Voice();

    PanelBridge(Context ctx) { this.ctx = ctx; }

    abstract void js(String code);

    TvDriver d() { return Tvs.driver(ctx); }

    static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    @JavascriptInterface public void press(String name, int dir) {
        Button b = Button.parse(name);
        if (b != null) d().press(b, dir);
    }
    @JavascriptInterface public void launch(String ref) { d().launch(ref); }
    @JavascriptInterface public void text(String t) { d().text(t); }
    @JavascriptInterface public void reconnect() { TvDriver c = d(); c.disconnect(); c.connect(); }
    @JavascriptInterface public String brand() { return d().brand(); }
    @JavascriptInterface public String state() {
        TvDriver c = d();
        try { return new JSONObject().put("state", c.host() == null ? "nohost" : c.state()).toString(); } catch (Exception e) { return "{}"; }
    }
    @JavascriptInterface public String supported() { return new JSONArray(d().supportedNames()).toString(); }

    /** Answers later with onApps([{name, ref}]) because Roku has to be asked over Wi-Fi. */
    @JavascriptInterface public void apps() {
        TvDriver c = d();
        new Thread(() -> {
            JSONArray a = new JSONArray();
            for (TvDriver.App x : c.apps()) try { a.put(new JSONObject().put("name", x.name).put("ref", x.ref)); } catch (Exception ignored) { }
            js("window.onApps&&onApps(" + a + ")");
        }).start();
    }

    @JavascriptInterface public String layout() { return Tvs.layout(ctx); }
    @JavascriptInterface public void saveLayout(String json) { Tvs.saveLayout(ctx, json); }
    @JavascriptInterface public String useLayout(String id) { return Tvs.useLayout(ctx, id); }
    @JavascriptInterface public String layouts() { return Tvs.layouts(ctx); }

    void startVoice() { voice.start(ctx, d(), this::js); }
}
