package com.remotemate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Roku TVs and sticks, using Roku's External Control Protocol (ECP): plain HTTP on port 8060.
 * No pairing. The TV needs Settings › System › Advanced system settings › Control by mobile apps = Enabled/Default.
 * Reference: Roku ECP docs, and the rokuecp library Home Assistant uses.
 */
public final class RokuDriver extends TvDriver {
    public static final int PORT = 8060;
    public static final String SSDP_ST = "roku:ecp";

    static final Map<Button, String> KEYS = new EnumMap<>(Button.class);
    static {
        String[] t = {
                "POWER", "Power", "MUTE", "VolumeMute", "VOL_UP", "VolumeUp", "VOL_DOWN", "VolumeDown",
                "CH_UP", "ChannelUp", "CH_DOWN", "ChannelDown", "LIVE_TV", "InputTuner",
                "HDMI1", "InputHDMI1", "HDMI2", "InputHDMI2", "HDMI3", "InputHDMI3", "HDMI4", "InputHDMI4", "INPUT", "InputAV1",
                "UP", "Up", "DOWN", "Down", "LEFT", "Left", "RIGHT", "Right", "OK", "Select", "BACK", "Back", "HOME", "Home",
                "INFO", "Info", "SETTINGS", "Info", "PLAY_PAUSE", "Play", "PLAY", "Play", "PAUSE", "Play",
                "REWIND", "Rev", "FFWD", "Fwd", "REPLAY", "InstantReplay", "SEARCH", "Search",
                "NUM_0", "Lit_0", "NUM_1", "Lit_1", "NUM_2", "Lit_2", "NUM_3", "Lit_3", "NUM_4", "Lit_4",
                "NUM_5", "Lit_5", "NUM_6", "Lit_6", "NUM_7", "Lit_7", "NUM_8", "Lit_8", "NUM_9", "Lit_9",
                "DEL", "Backspace", "ENTER", "Enter", "FIND_REMOTE", "FindRemote",
        };
        for (int i = 0; i < t.length; i += 2) KEYS.put(Button.valueOf(t[i]), t[i + 1]);
    }

    /** Keeps presses in order without blocking the UI. */
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "roku-io"); t.setDaemon(true); return t; });
    private volatile boolean wantConnected;
    private volatile String deviceName = "";
    private int generation;

    @Override public String brand() { return "roku"; }
    @Override public String brandName() { return "Roku"; }
    @Override public Pairing pairing() { return Pairing.NONE; }
    @Override public boolean supports(Button b) { return KEYS.containsKey(b); }
    public String deviceName() { return deviceName; }

    private String base() { return "http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + PORT; }

    /** One HTTP request. Returns the body for GET, "" for POST. Throws with a friendly message. */
    static String http(String method, String url, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(java.net.Proxy.NO_PROXY);
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        if (method.equals("POST")) { c.setDoOutput(true); c.setFixedLengthStreamingMode(0); c.getOutputStream().close(); }
        try {
            int code = c.getResponseCode();
            if (code == 403) throw new IOException("ROKU_403");
            if (code >= 300) throw new IOException("The Roku said no (HTTP " + code + ").");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    static String friendly(Exception e) {
        if ("ROKU_403".equals(e.getMessage()))
            return "The Roku blocked the phone. On the TV: Settings › System › Advanced system settings › Control by mobile apps › Enabled.";
        if (e instanceof ConnectException || e instanceof NoRouteToHostException || e instanceof SocketTimeoutException)
            return "Can't reach the Roku. Is it on, and on the same Wi-Fi?";
        return String.valueOf(e.getMessage());
    }

    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">([^<]*)</" + name + ">").matcher(xml);
        return m.find() ? unescape(m.group(1).trim()) : "";
    }

    static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** Checks a Roku answers at this address. Returns its name. Blocking. */
    public static String probe(String h, int timeoutMs) throws IOException {
        String xml = http("GET", "http://" + h + ":" + PORT + "/query/device-info", timeoutMs);
        if (!xml.contains("<device-info")) throw new IOException("Not a Roku.");
        String name = tag(xml, "user-device-name");
        if (name.isEmpty()) name = tag(xml, "friendly-device-name");
        if (name.isEmpty()) name = tag(xml, "model-name");
        return name.isEmpty() ? "Roku" : name;
    }

    /** No pairing on Roku: just checks it answers. */
    @Override
    public void pairStart(String h, String phoneName) throws Exception {
        try {
            deviceName = probe(h, 4000);
        } catch (IOException e) {
            throw new IOException(friendly(e));
        }
        host = h;
    }

    @Override
    public void connect() {
        wantConnected = true;
        if (host == null || state.equals("connected") || state.equals("connecting")) return;
        final int gen;
        synchronized (this) { gen = ++generation; }
        setState("connecting", host);
        io.execute(() -> check(gen));
    }

    private void check(int gen) {
        try {
            String xml = http("GET", base() + "/query/device-info", 4000);
            synchronized (this) { if (gen != generation) return; }
            String n = tag(xml, "user-device-name");
            if (!n.isEmpty()) deviceName = n;
            setState("connected", host);
            Listener l = listener;
            if (l != null) l.onPower(!"PowerOff".equals(tag(xml, "power-mode")) && !"DisplayOff".equals(tag(xml, "power-mode")));
        } catch (Exception e) {
            synchronized (this) { if (gen != generation) return; }
            setState("offline", friendly(e));
            if (wantConnected && !"ROKU_403".equals(e.getMessage())) {
                Thread t = new Thread(() -> {
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                    synchronized (this) { if (gen != generation || !wantConnected) return; }
                    if (state.equals("offline")) connect();
                });
                t.setDaemon(true);
                t.start();
            }
        }
    }

    @Override
    public void disconnect() {
        wantConnected = false;
        synchronized (this) { generation++; }
        setState("offline", "");
    }

    private void post(String path) {
        final String h = host;
        if (h == null) return;
        io.execute(() -> {
            try {
                http("POST", base() + path, 3000);
                if (!state.equals("connected")) setState("connected", h);
            } catch (Exception e) {
                setState("offline", friendly(e));
            }
        });
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    @Override
    public void press(Button b, int direction) {
        String k = KEYS.get(b);
        if (k == null) return;
        post((direction == Button.START_LONG ? "/keydown/" : direction == Button.END_LONG ? "/keyup/" : "/keypress/") + k);
    }

    /** appRef = Roku channel id, e.g. "12" for Netflix. */
    @Override
    public void launch(String appRef) {
        if (appRef == null || appRef.isEmpty()) return;
        post("/launch/" + enc(appRef));
    }

    /** Roku types one character at a time ("Lit_" keys). */
    @Override
    public void text(String text) {
        if (text == null) return;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            post("/keypress/Lit_" + enc(new String(Character.toChars(cp))));
            i += Character.charCount(cp);
        }
    }

    /** Voice on Roku: the phone hears the words, then the Roku opens its search with them. */
    @Override
    public void search(String text) {
        if (text == null || text.trim().isEmpty()) return;
        post("/search/browse?keyword=" + enc(text.trim()));
    }

    /** Asks the Roku which channels are installed. */
    @Override
    public List<App> apps() {
        List<App> out = new ArrayList<>();
        try {
            String xml = http("GET", base() + "/query/apps", 4000);
            Matcher m = Pattern.compile("<app id=\"([^\"]+)\"[^>]*>([^<]*)</app>").matcher(xml);
            while (m.find()) out.add(new App(unescape(m.group(2).trim()), m.group(1)));
        } catch (Exception ignored) { }
        if (out.isEmpty()) {
            String[][] t = {{"Netflix", "12"}, {"YouTube", "837"}, {"Prime Video", "13"}, {"Disney+", "291097"}, {"Apple TV", "551012"}, {"Plex", "13535"}, {"Spotify", "22297"}};
            for (String[] x : t) out.add(new App(x[0], x[1]));
        }
        return out;
    }
}
