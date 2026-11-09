package com.remotemate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One TV platform (Google TV, Roku, ...). Pure Java, no Android classes, so drivers can be tested off the phone.
 * The UI talks only to this class, using generic {@link Button}s.
 */
public abstract class TvDriver {

    /** How a brand pairs. CODE = type a code shown on the TV. ALLOW = press Allow on the TV. NONE = nothing to do. */
    public enum Pairing { NONE, CODE, ALLOW }

    public interface Listener {
        /** state: offline, connecting, connected, needs_pairing */
        void onState(String state, String detail);
        void onVolume(int level, int max, boolean muted);
        void onTextField(boolean show, String label);
        void onPower(boolean on);
    }

    /** An app the TV can open. ref is driver-specific (Google TV: a link; Roku: a channel id). */
    public static final class App {
        public final String name, ref;
        public App(String name, String ref) { this.name = name; this.ref = ref; }
    }

    protected volatile Listener listener;
    protected volatile String host;
    protected volatile String state = "offline";

    /** Short id used in saved settings and layouts: "googletv", "roku". */
    public abstract String brand();

    /** Name shown to the user. */
    public abstract String brandName();

    public abstract Pairing pairing();

    public void setListener(Listener l) { listener = l; }
    public String host() { return host; }
    public String state() { return state; }

    protected void setState(String s, String detail) {
        state = s;
        Listener l = listener;
        if (l != null) l.onState(s, detail == null ? "" : detail);
    }

    public void setHost(String h) {
        if (h != null && !h.equals(host)) { host = h; disconnect(); }
    }

    /** Step 1 of pairing. For CODE brands the TV now shows a code. Blocking. */
    public abstract void pairStart(String host, String phoneName) throws Exception;

    /** Step 2 of pairing (CODE brands only). Blocking. */
    public void pairFinish(String code) throws Exception { }

    public void pairCancel() { }

    /** Connects in the background. No-op if already connected or connecting. */
    public abstract void connect();

    public abstract void disconnect();

    public abstract boolean supports(Button b);

    /** direction: Button.SHORT, START_LONG or END_LONG. */
    public abstract void press(Button b, int direction);

    public abstract void launch(String appRef);

    /** Types text into the box that's focused on the TV. */
    public abstract void text(String text);

    /** Apps worth offering in the button editor. May block (asks the TV). */
    public List<App> apps() { return new ArrayList<>(); }

    // ---- voice ----

    /** True if the phone mic can be streamed straight to the TV (Google TV). Otherwise the phone turns speech into text. */
    public boolean voiceStream() { return false; }

    public int voiceStart() { return -1; }

    public void voiceChunk(int session, byte[] pcm, int len) throws IOException { }

    public void voiceEnd(int session) { }

    /** Runs a search on the TV with text the phone heard (brands without voice streaming). */
    public void search(String text) { text(text); }

    public List<String> supportedNames() {
        List<String> out = new ArrayList<>();
        for (Button b : Button.values()) if (supports(b)) out.add(b.name());
        return out;
    }
}
