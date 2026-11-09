package com.remotemate;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONObject;

import java.util.ArrayList;

/**
 * The voice button, shared by the edge panel and the window.
 * Google TV: the phone mic is streamed to the TV (16-bit PCM, mono, 8 kHz), exactly as in v1.2.
 * Other TVs: the phone turns speech into text on the phone, then the TV runs a search with it.
 */
final class Voice {
    interface Out { void js(String code); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean on;
    private SpeechRecognizer recognizer;

    boolean on() { return on; }

    void stop() {
        on = false;
        main.post(() -> { if (recognizer != null) recognizer.stopListening(); });
    }

    private static String q(String s) { return JSONObject.quote(s == null ? "" : s); }

    void start(Context ctx, TvDriver c, Out out) {
        if (on) return;
        on = true;
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            on = false;
            out.js("onVoice('noperm')");
            return;
        }
        if (c.voiceStream()) stream(c, out);
        else main.post(() -> speechToText(ctx, c, out));
    }

    // ---------- Google TV: stream the mic ----------

    @android.annotation.SuppressLint("MissingPermission") // checked in start()
    private void stream(TvDriver c, Out out) {
        new Thread(() -> {
            int session = c.voiceStart();
            if (session < 0) {
                on = false;
                out.js("onVoice('unsupported')");
                return;
            }
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 8000, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 16384) * 2);
                rec.startRecording();
                out.js("onVoice('listening')");
                byte[] buf = new byte[8192]; // 0.5 s of audio
                long start = System.currentTimeMillis(), lastLoud = 0;
                boolean heard = false, allSilent = true;
                while (on) {
                    int got = 0;
                    while (got < buf.length && on) {
                        int n = rec.read(buf, got, buf.length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    if (got <= 0) break;
                    c.voiceChunk(session, buf, got);
                    double rms = rms(buf, got);
                    long now = System.currentTimeMillis();
                    if (rms > 0) allSilent = false;
                    if (rms > 900) { heard = true; lastLoud = now; }
                    if (heard && now - lastLoud > 1300) break;          // stopped talking
                    if (!heard && now - start > 6000) break;             // never started
                    if (now - start > 15000) break;                       // safety limit
                }
                if (allSilent) out.js("onVoice('error','The phone mic gave no sound. Open RemoteMate once, then try again.')");
            } catch (Exception e) {
                out.js("onVoice('error'," + q("Voice stopped: " + e.getMessage()) + ")");
            } finally {
                if (rec != null) { try { rec.stop(); } catch (Exception ignored) { } rec.release(); }
                c.voiceEnd(session);
                on = false;
                out.js("onVoice('idle')");
            }
        }, "voice").start();
    }

    static double rms(byte[] b, int n) {
        long sum = 0;
        int count = n / 2;
        for (int i = 0; i + 1 < n; i += 2) { int s = (short) ((b[i] & 0xff) | (b[i + 1] << 8)); sum += (long) s * s; }
        return count == 0 ? 0 : Math.sqrt(sum / (double) count);
    }

    // ---------- other TVs: speech → text on the phone → search on the TV ----------

    private void speechToText(Context ctx, TvDriver c, Out out) {
        boolean onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
        if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(ctx)) {
            on = false;
            out.js("onVoice('unsupported','This phone has no speech recognition. Use the keyboard in the ••• menu.')");
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) : SpeechRecognizer.createSpeechRecognizer(ctx);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle b) { out.js("onVoice('listening')"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float v) { }
            @Override public void onBufferReceived(byte[] b) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onPartialResults(Bundle b) { }
            @Override public void onEvent(int t, Bundle b) { }
            @Override public void onError(int e) {
                on = false;
                out.js(e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "onVoice('error','Didn’t catch that. Try again.')" : "onVoice('error','Voice stopped (error " + e + ").')");
                out.js("onVoice('idle')");
            }
            @Override public void onResults(Bundle b) {
                on = false;
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String text = r == null || r.isEmpty() ? "" : r.get(0);
                if (!text.isEmpty()) { c.search(text); out.js("onVoice('heard'," + q(text) + ")"); }
                out.js("onVoice('idle')");
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(i);
    }

    void destroy() {
        on = false;
        main.post(() -> { if (recognizer != null) { recognizer.destroy(); recognizer = null; } });
    }
}
