package com.remotemate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * Google TV / Android TV over Wi-Fi, the same way the Google TV phone remote does.
 * Port 6467 = pairing (TV shows a 6-character code), port 6466 = remote control.
 * Protocol details learned from androidtvremote2 (Apache-2.0, tronikos). This is the v1.2 AtvClient, unchanged
 * apart from sitting behind TvDriver.
 */
public final class GoogleTvDriver extends TvDriver {
    public static final int PAIR_PORT = 6467, REMOTE_PORT = 6466;
    public static final String MDNS_TYPE = "_androidtvremote2._tcp";

    static final int SEARCH = 84, OK = 23;

    /** Generic button → Android KeyEvent code. */
    static final Map<Button, Integer> CODES = new EnumMap<>(Button.class);
    static {
        Object[] t = {
                Button.POWER, 26, Button.MUTE, 164, Button.VOL_UP, 24, Button.VOL_DOWN, 25, Button.CH_UP, 166, Button.CH_DOWN, 167,
                Button.INPUT, 178, Button.USB, 178, Button.HDMI1, 243, Button.HDMI2, 244, Button.HDMI3, 245, Button.HDMI4, 246,
                Button.LIVE_TV, 170, Button.UP, 19, Button.DOWN, 20, Button.LEFT, 21, Button.RIGHT, 22, Button.OK, 23,
                Button.BACK, 4, Button.HOME, 3, Button.SETTINGS, 176, Button.PROFILE, 288, Button.GUIDE, 172, Button.INFO, 165,
                Button.MENU, 82, Button.APPS, 284, Button.PLAY_PAUSE, 85, Button.PLAY, 126, Button.PAUSE, 127, Button.STOP, 86,
                Button.REWIND, 89, Button.FFWD, 90, Button.PREV, 88, Button.NEXT, 87, Button.SEARCH, 84, Button.CAPTIONS, 175,
                Button.RED, 183, Button.GREEN, 184, Button.YELLOW, 185, Button.BLUE, 186,
                Button.NUM_0, 7, Button.NUM_1, 8, Button.NUM_2, 9, Button.NUM_3, 10, Button.NUM_4, 11,
                Button.NUM_5, 12, Button.NUM_6, 13, Button.NUM_7, 14, Button.NUM_8, 15, Button.NUM_9, 16,
                Button.DEL, 67, Button.ENTER, 66,
        };
        for (int i = 0; i < t.length; i += 2) CODES.put((Button) t[i], (Integer) t[i + 1]);
    }

    // Features we ask the TV for: ping, keys, typing, voice, power, volume, app links
    private static final int FEATURES = 1 | 2 | 4 | 8 | 32 | 64 | 512;

    private final File keyFile;
    private KeyStore keyStore;
    private SSLContext ssl;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tv-io"); t.setDaemon(true); return t; });
    private final Object writeLock = new Object();
    private volatile SSLSocket remote;
    private volatile OutputStream remoteOut;
    private volatile int activeFeatures = FEATURES;
    private volatile int imeCounter, fieldCounter;
    private volatile CompletableFuture<Integer> voiceBegin;
    private volatile boolean wantConnected;
    private int generation;

    // pairing
    private SSLSocket pairSock;

    public GoogleTvDriver(File keyFile) { this.keyFile = keyFile; }

    @Override public String brand() { return "googletv"; }
    @Override public String brandName() { return "Google TV"; }
    @Override public Pairing pairing() { return Pairing.CODE; }
    @Override public boolean voiceStream() { return (activeFeatures & 8) != 0; }
    @Override public boolean supports(Button b) { return CODES.containsKey(b); }

    private synchronized SSLContext ssl() throws Exception {
        if (ssl == null) {
            keyStore = Certs.loadOrCreate(keyFile);
            ssl = Certs.context(keyStore, PAIR_PORT, pins);
        }
        return ssl;
    }

    /** TV certificate fingerprints by host, saved next to the phone's key. */
    private final Certs.Pins pins = new Certs.Pins() {
        private File file() { return new File(keyFile.getParentFile(), "tv-pins.properties"); }
        private synchronized Properties load() {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(file())) { p.load(in); } catch (Exception ignored) { }
            return p;
        }
        @Override public String get(String host) { return load().getProperty(host); }
        @Override public synchronized void put(String host, String fp) {
            Properties p = load();
            p.setProperty(host, fp);
            try (FileOutputStream out = new FileOutputStream(file())) { p.store(out, "RemoteMate: paired TV certificates"); } catch (Exception ignored) { }
        }
    };

    private SSLSocket open(String h, int port, int timeoutMs) throws Exception {
        SSLSocket s = (SSLSocket) ssl().getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(h, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        s.setTcpNoDelay(true);
        s.startHandshake();
        return s;
    }

    // =============== Pairing ===============

    private static Proto.W polo() { return new Proto.W().num(1, 2).num(2, 200); }

    private static Proto.W encoding() { return new Proto.W().num(1, 3).num(2, 6); } // hexadecimal, 6 characters

    private Proto.R pairRead() throws IOException {
        Proto.R m = new Proto.R(Proto.readFrame(pairSock.getInputStream()));
        long status = m.num(2);
        if (status != 200) throw new IOException(status == 402 ? "That code wasn't right." : "The TV said no (status " + status + ").");
        return m;
    }

    private void pairWrite(Proto.W m) throws IOException { Proto.writeFrame(pairSock.getOutputStream(), m.toBytes()); }

    /** Step 1: connects and asks the TV to show a pairing code. Blocking. */
    @Override
    public synchronized void pairStart(String h, String phoneName) throws Exception {
        pairCancel();
        pairSock = open(h, PAIR_PORT, 8000);
        pairSock.setSoTimeout(15000);
        pairWrite(polo().msg(10, new Proto.W().str(1, "atvremote").str(2, phoneName)));
        Proto.R m = pairRead();
        if (!m.has(11)) throw new IOException("Unexpected reply from the TV.");
        pairWrite(polo().msg(20, new Proto.W().msg(1, encoding()).num(3, 1)));
        m = pairRead();
        if (!m.has(20)) throw new IOException("Unexpected reply from the TV.");
        pairWrite(polo().msg(30, new Proto.W().msg(1, encoding()).num(2, 1)));
        m = pairRead();
        if (!m.has(31)) throw new IOException("Unexpected reply from the TV.");
        host = h;
    }

    /** Step 2: sends the code the TV showed. Blocking. */
    @Override
    public synchronized void pairFinish(String code) throws Exception {
        if (pairSock == null) throw new IOException("Start pairing again.");
        code = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.matches("[0-9A-F]{6}")) throw new IOException("The code is 6 letters/numbers (0–9, A–F).");
        byte[] secret = secretFor(code);
        if ((secret[0] & 0xff) != Integer.parseInt(code.substring(0, 2), 16)) throw new IOException("That code doesn't match. Check it on the TV.");
        pairWrite(polo().msg(40, new Proto.W().bytes(1, secret)));
        Proto.R m = pairRead();
        if (!m.has(41)) throw new IOException("The TV didn't accept the code.");
        // remember this TV's certificate: the remote port must show the same one from now on
        pins.put(pairSock.getInetAddress().getHostAddress(), Certs.fingerprint(pairSock.getSession().getPeerCertificates()[0]));
        pairCancel();
    }

    byte[] secretFor(String code) throws Exception {
        RSAPublicKey mine = (RSAPublicKey) Certs.clientCert(keyStore).getPublicKey();
        RSAPublicKey tv = (RSAPublicKey) ((X509Certificate) pairSock.getSession().getPeerCertificates()[0]).getPublicKey();
        MessageDigest h = MessageDigest.getInstance("SHA-256");
        h.update(Certs.magnitude(mine.getModulus()));
        h.update(Certs.magnitude(mine.getPublicExponent()));
        h.update(Certs.magnitude(tv.getModulus()));
        h.update(Certs.magnitude(tv.getPublicExponent()));
        h.update(hex(code.substring(2)));
        return h.digest();
    }

    @Override
    public synchronized void pairCancel() {
        if (pairSock != null) { try { pairSock.close(); } catch (Exception ignored) { } pairSock = null; }
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    // =============== Remote ===============

    @Override
    public void connect() {
        wantConnected = true;
        if (host == null || state.equals("connected") || state.equals("connecting")) return;
        final int gen;
        synchronized (this) { gen = ++generation; }
        setState("connecting", host);
        io.execute(() -> runRemote(gen));
    }

    @Override
    public void disconnect() {
        wantConnected = false;
        synchronized (this) { generation++; }
        closeRemote();
        if (!state.equals("needs_pairing")) setState("offline", "");
    }

    private void closeRemote() {
        SSLSocket s = remote;
        remote = null;
        remoteOut = null;
        if (s != null) try { s.close(); } catch (Exception ignored) { }
    }

    private void runRemote(int gen) {
        SSLSocket s;
        try {
            s = open(host, REMOTE_PORT, 6000);
            s.setSoTimeout(20000); // TV pings every ~5s
        } catch (SSLException e) {
            setState("needs_pairing", "The TV doesn't know this phone yet. Pair it in Settings.");
            return;
        } catch (ConnectException | NoRouteToHostException | SocketTimeoutException e) {
            setState("offline", "Can't reach the TV. Is it on, and on the same Wi-Fi?");
            scheduleRetry(gen);
            return;
        } catch (Exception e) {
            setState("offline", String.valueOf(e.getMessage()));
            scheduleRetry(gen);
            return;
        }
        synchronized (this) { if (gen != generation) { try { s.close(); } catch (Exception ignored) { } return; } }
        remote = s;
        try {
            remoteOut = s.getOutputStream();
            InputStream in = s.getInputStream();
            while (true) handle(new Proto.R(Proto.readFrame(in)));
        } catch (Exception e) {
            boolean current;
            synchronized (this) { current = gen == generation; }
            if (current) {
                closeRemote();
                CompletableFuture<Integer> vb = voiceBegin;
                if (vb != null) vb.complete(null);
                if ((e instanceof SSLException || e instanceof java.io.EOFException) && !state.equals("connected")) {
                    setState("needs_pairing", "The TV doesn't know this phone yet. Pair it in Settings.");
                } else {
                    setState("offline", "Lost connection to the TV.");
                    scheduleRetry(gen);
                }
            }
        }
    }

    private void scheduleRetry(int gen) {
        if (!wantConnected) return;
        Thread t = new Thread(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
            synchronized (this) { if (gen != generation || !wantConnected) return; }
            if (state.equals("offline")) connect();
        });
        t.setDaemon(true);
        t.start();
    }

    private void handle(Proto.R m) throws IOException {
        Listener l = listener;
        if (m.has(1)) { // configure
            int supported = (int) m.msg(1).num(1);
            activeFeatures = FEATURES & (supported == 0 ? FEATURES : supported);
            send(new Proto.W().msg(1, new Proto.W().num(1, activeFeatures)
                    .msg(2, new Proto.W().num(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0"))));
        } else if (m.has(2)) { // set active
            send(new Proto.W().msg(2, new Proto.W().num(1, activeFeatures)));
        } else if (m.has(8)) { // ping
            send(new Proto.W().msg(9, new Proto.W().num(1, m.msg(8).num(1))));
        } else if (m.has(40)) { // remote start
            boolean on = m.msg(40).num(1) != 0;
            setState("connected", host);
            if (l != null) l.onPower(on);
        } else if (m.has(50)) { // volume
            Proto.R v = m.msg(50);
            if (l != null) l.onVolume((int) v.num(7), (int) v.num(6), v.num(8) != 0);
        } else if (m.has(21)) { // ime batch edit
            Proto.R b = m.msg(21);
            imeCounter = (int) b.num(1);
            fieldCounter = (int) b.num(2);
        } else if (m.has(22)) { // TV wants text
            Proto.R st = m.msg(22).msg(2);
            if (st.has(1)) fieldCounter = (int) st.num(1);
            if (l != null) l.onTextField(true, st.str(6));
        } else if (m.has(30)) { // voice begin
            CompletableFuture<Integer> vb = voiceBegin;
            if (vb != null) vb.complete((int) m.msg(30).num(1));
        }
        if (!state.equals("connected") && (m.has(2) || m.has(8))) setState("connected", host);
    }

    private void send(Proto.W m) throws IOException {
        OutputStream o = remoteOut;
        if (o == null) throw new IOException("Not connected");
        synchronized (writeLock) { Proto.writeFrame(o, m.toBytes()); }
    }

    private void sendAsync(Proto.W m) {
        if (remoteOut == null) { connect(); return; }
        Thread t = new Thread(() -> { try { send(m); } catch (IOException e) { closeRemote(); connect(); } });
        t.setDaemon(true);
        t.start();
    }

    /** Raw Android key code (kept for tests and anything not in Button). */
    public void key(int code, int direction) {
        sendAsync(new Proto.W().msg(10, new Proto.W().num(1, code).num(2, direction)));
    }

    @Override
    public void press(Button b, int direction) {
        Integer code = CODES.get(b);
        if (code != null) key(code, direction);
    }

    @Override
    public void launch(String link) {
        sendAsync(new Proto.W().msg(90, new Proto.W().str(1, link)));
    }

    @Override
    public void text(String text) {
        if (text == null || text.isEmpty()) return;
        int pos = text.length() - 1;
        sendAsync(new Proto.W().msg(21, new Proto.W().num(1, imeCounter).num(2, fieldCounter)
                .msg(3, new Proto.W().num(1, 1).msg(2, new Proto.W().num(1, pos).num(2, pos).str(3, text)))));
    }

    /** Common Google TV apps. The protocol can't list what's installed, so these open by package name. */
    @Override
    public List<App> apps() {
        List<App> a = new ArrayList<>();
        String[][] t = {
                {"YouTube", "com.google.android.youtube.tv"}, {"Netflix", "com.netflix.ninja"},
                {"Prime Video", "com.amazon.amazonvideo.livingroom"}, {"Disney+", "com.disney.disneyplus"},
                {"Stan", "au.com.stan.and"}, {"Binge", "au.com.streamotion.ares.tv"}, {"Kayo", "au.com.kayosports.tv"},
                {"ABC iview", "air.au.com.abc.iview"}, {"SBS On Demand", "com.sbs.ondemand.tv"}, {"9Now", "au.com.ninenow.ctv"},
                {"7plus", "au.com.seven.tv"}, {"10 Play", "au.com.tenplay.tv"}, {"Plex", "com.plexapp.android"},
                {"Spotify", "com.spotify.tv.android"}, {"Twitch", "tv.twitch.android.app"}, {"Apple TV", "com.apple.atve.androidtv.appletv"},
                {"SmartTube", "com.teamsmart.videomanager.tv"}, {"Kodi", "org.xbmc.kodi"}, {"VLC", "org.videolan.vlc"},
        };
        for (String[] x : t) a.add(new App(x[0], "market://launch?id=" + x[1]));
        return a;
    }

    // ---------- voice: 16-bit PCM, mono, 8000 Hz ----------

    /** Asks the TV to start listening. Returns a session id, or -1 if the TV didn't start voice. Blocking (≤2.5s). */
    @Override
    public int voiceStart() {
        if (remoteOut == null) return -1;
        CompletableFuture<Integer> f = new CompletableFuture<>();
        voiceBegin = f;
        try {
            send(new Proto.W().msg(10, new Proto.W().num(1, SEARCH).num(2, Button.SHORT)));
            Integer id = f.get(2500, TimeUnit.MILLISECONDS);
            if (id == null) return -1;
            send(new Proto.W().msg(30, new Proto.W().num(1, id)));
            return id;
        } catch (Exception e) {
            return -1;
        } finally {
            voiceBegin = null;
        }
    }

    /** Sends audio. Chunks must be 8–20 KB; this pads/splits as needed. */
    @Override
    public void voiceChunk(int session, byte[] pcm, int len) throws IOException {
        for (int i = 0; i < len; i += 20 * 1024) {
            int n = Math.min(20 * 1024, len - i);
            byte[] part = new byte[Math.max(n, 8 * 1024)];
            System.arraycopy(pcm, i, part, 0, n);
            send(new Proto.W().msg(31, new Proto.W().num(1, session).bytes(2, part)));
        }
    }

    @Override
    public void voiceEnd(int session) {
        try { send(new Proto.W().msg(32, new Proto.W().num(1, session))); } catch (IOException ignored) { }
    }
}
