package com.remotemate;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Saved TVs, the current TV's driver, and button layouts.
 * TVs live in prefs "tvs" as a JSON list of {id, brand, host, name}. "current" is the id in use.
 * Layouts: presets in assets/layouts/*.json, "My layout" per TV in prefs "layout:<id>".
 */
public final class Tvs {
    private Tvs() {}

    private static TvDriver driver;
    private static String driverFor = "";

    public static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("remote", Context.MODE_PRIVATE); }

    public static JSONArray list(Context c) {
        try { return new JSONArray(prefs(c).getString("tvs", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    public static JSONObject current(Context c) {
        String id = prefs(c).getString("current", "");
        JSONArray a = list(c);
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optJSONObject(i).optString("id"))) return a.optJSONObject(i);
        return null;
    }

    static String id(String brand, String host) { return brand + "@" + host; }

    /** Saves (or updates) a TV and makes it the current one. */
    public static void add(Context c, String brand, String host, String name) {
        String id = id(brand, host);
        JSONArray a = list(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optJSONObject(i).optString("id"))) out.put(a.optJSONObject(i));
        try { out.put(new JSONObject().put("id", id).put("brand", brand).put("host", host).put("name", name)); } catch (Exception ignored) { }
        prefs(c).edit().putString("tvs", out.toString()).putString("current", id).apply();
    }

    public static void use(Context c, String id) { prefs(c).edit().putString("current", id).apply(); }

    public static void forget(Context c, String id) {
        JSONArray a = list(c), out = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optJSONObject(i).optString("id"))) out.put(a.optJSONObject(i));
        SharedPreferences.Editor e = prefs(c).edit().putString("tvs", out.toString()).remove("layout:" + id).remove("layoutId:" + id);
        if (id.equals(prefs(c).getString("current", ""))) e.putString("current", out.length() > 0 ? out.optJSONObject(out.length() - 1).optString("id") : "");
        e.apply();
    }

    /** A driver for a brand, sharing the phone's Google TV key. */
    static TvDriver make(Context c, String brand) {
        if ("roku".equals(brand)) return new RokuDriver();
        return new GoogleTvDriver(new File(c.getFilesDir(), "remote-key.p12"));
    }

    /** The driver for the current TV (same object until the TV changes). Host may be null if no TV is set up. */
    public static synchronized TvDriver driver(Context c) {
        JSONObject tv = current(c);
        String brand = tv == null ? "googletv" : tv.optString("brand", "googletv");
        String key = tv == null ? "" : tv.optString("id");
        if (driver == null || !key.equals(driverFor)) {
            TvDriver.Listener l = driver == null ? null : driver.listener;
            if (driver != null) { driver.setListener(null); driver.disconnect(); }
            driver = make(c, brand);
            driver.setListener(l);
            driverFor = key;
        }
        if (tv != null) driver.setHost(tv.optString("host"));
        return driver;
    }

    // ================= layouts =================

    static final String[] PRESETS = {"googletv", "roku", "alex"};

    static String asset(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) { return null; }
    }

    /** The layout JSON for the current TV. */
    public static String layout(Context c) {
        JSONObject tv = current(c);
        String id = tv == null ? "" : tv.optString("id");
        String brand = tv == null ? "googletv" : tv.optString("brand", "googletv");
        String which = prefs(c).getString("layoutId:" + id, "");
        if ("mine".equals(which)) {
            String mine = prefs(c).getString("layout:" + id, null);
            if (mine != null) return mine;
        }
        String p = asset(c, "layouts/" + (which.isEmpty() || "mine".equals(which) ? brand : which) + ".json");
        return p != null ? p : asset(c, "layouts/googletv.json");
    }

    /** Saves the edited layout as "My layout" for the current TV and selects it. */
    public static void saveLayout(Context c, String json) {
        JSONObject tv = current(c);
        String id = tv == null ? "" : tv.optString("id");
        prefs(c).edit().putString("layout:" + id, json).putString("layoutId:" + id, "mine").apply();
    }

    /** Picks a preset (or "mine") for the current TV. Returns its JSON. */
    public static String useLayout(Context c, String which) {
        JSONObject tv = current(c);
        String id = tv == null ? "" : tv.optString("id");
        prefs(c).edit().putString("layoutId:" + id, which).apply();
        return layout(c);
    }

    /** [{id, name, current}] for the layout picker. */
    public static String layouts(Context c) {
        JSONObject tv = current(c);
        String id = tv == null ? "" : tv.optString("id");
        String brand = tv == null ? "googletv" : tv.optString("brand", "googletv");
        String which = prefs(c).getString("layoutId:" + id, "");
        if (which.isEmpty()) which = brand;
        JSONArray a = new JSONArray();
        try {
            if (prefs(c).getString("layout:" + id, null) != null) a.put(new JSONObject().put("id", "mine").put("name", "My layout").put("current", "mine".equals(which)));
            for (String p : PRESETS) {
                String j = asset(c, "layouts/" + p + ".json");
                if (j == null) continue;
                a.put(new JSONObject().put("id", p).put("name", new JSONObject(j).optString("name", p)).put("current", p.equals(which)));
            }
        } catch (Exception ignored) { }
        return a.toString();
    }
}
