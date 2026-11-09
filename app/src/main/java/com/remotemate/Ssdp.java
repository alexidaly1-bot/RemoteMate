package com.remotemate;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds TVs with SSDP (UPnP) multicast: Roku, plus Samsung, LG, Fire TV and others that answer DIAL.
 * Google TVs are found separately with mDNS (Android NsdManager), see Finder.
 * Pure Java so it can be tested off the phone.
 */
public final class Ssdp {
    public static final String[] TARGETS = {
            RokuDriver.SSDP_ST,
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:lge-com:service:webos-second-screen:1",
    };

    /** A TV that was found. brand: googletv, roku, samsung, lg, firetv, sony, other. */
    public static final class Tv {
        public final String host, brand, name, model;
        public Tv(String host, String brand, String name, String model) { this.host = host; this.brand = brand; this.name = name; this.model = model; }
        @Override public String toString() { return brand + " " + name + " @" + host; }
    }

    public interface Callback { void found(Tv tv); }

    private final String group;
    private final int port;
    private volatile boolean stopped;
    private DatagramSocket sock;
    private final ExecutorService fetch = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "ssdp-desc"); t.setDaemon(true); return t; });

    public Ssdp() { this("239.255.255.250", 1900); }

    /** For tests: a different address/port. */
    public Ssdp(String group, int port) { this.group = group; this.port = port; }

    /** Searches for ms milliseconds, calling back for each new TV (once per address). Blocking. */
    public void search(int ms, Callback cb) {
        Set<String> seen = new HashSet<>();
        try {
            sock = new DatagramSocket();
            sock.setSoTimeout(250);
            InetAddress g = InetAddress.getByName(group);
            long end = System.currentTimeMillis() + ms, nextSend = 0;
            int rounds = 0;
            byte[] buf = new byte[4096];
            while (!stopped && System.currentTimeMillis() < end) {
                if (rounds < 3 && System.currentTimeMillis() >= nextSend) {
                    for (String st : TARGETS) {
                        byte[] m = ("M-SEARCH * HTTP/1.1\r\nHOST: " + group + ":" + port + "\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: " + st + "\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII);
                        sock.send(new DatagramPacket(m, m.length, g, port));
                    }
                    rounds++;
                    nextSend = System.currentTimeMillis() + 1000;
                }
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                String reply = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                String host = p.getAddress().getHostAddress();
                String st = header(reply, "ST"), loc = header(reply, "LOCATION"), server = header(reply, "SERVER");
                String key = host + "|" + (st.equals(RokuDriver.SSDP_ST) ? "roku" : "x");
                if (!seen.add(key)) continue;
                fetch.execute(() -> {
                    Tv tv = describe(host, st, loc, server);
                    if (tv != null && !stopped) cb.found(tv);
                });
            }
        } catch (IOException ignored) {
        } finally {
            if (sock != null) sock.close();
        }
    }

    public void stop() { stopped = true; if (sock != null) sock.close(); }

    static String header(String reply, String name) {
        Matcher m = Pattern.compile("(?im)^" + name + ":\\s*(.*?)\\s*$").matcher(reply);
        return m.find() ? m.group(1) : "";
    }

    /** Works out the brand from the SSDP reply and the device description XML. */
    static Tv describe(String host, String st, String location, String server) {
        String xml = "";
        if (!location.isEmpty()) try { xml = RokuDriver.http("GET", location, 2500); } catch (Exception ignored) { }
        String maker = RokuDriver.tag(xml, "manufacturer"), name = RokuDriver.tag(xml, "friendlyName"), model = RokuDriver.tag(xml, "modelName");
        String all = (st + " " + server + " " + maker + " " + model).toLowerCase(Locale.ROOT);
        String brand;
        if (st.equals(RokuDriver.SSDP_ST) || all.contains("roku")) brand = "roku";
        else if (all.contains("samsung")) brand = "samsung";
        else if (all.contains("lg electronics") || all.contains("webos") || all.contains("lge")) brand = "lg";
        else if (all.contains("amazon")) brand = "firetv";
        else if (all.contains("google") || all.contains("android")) brand = "googletv";
        else if (all.contains("sony")) brand = "sony";
        else if (st.contains("dial")) brand = "other";
        else return null;
        if (brand.equals("roku")) {
            try { name = RokuDriver.probe(host, 2500); } catch (Exception ignored) { }
        }
        if (name.isEmpty()) name = maker.isEmpty() ? "TV" : maker;
        return new Tv(host, brand, name, model);
    }

    // ---------- typed-in address ----------

    static boolean portOpen(String host, int port, int ms) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), ms); return true; } catch (Exception e) { return false; }
    }

    /** Works out what kind of TV is at a typed-in address. Returns null if nothing answers. Blocking (≤ ~2 s). */
    public static Tv probe(String host) {
        try { return new Tv(host, "roku", RokuDriver.probe(host, 2000), ""); } catch (Exception ignored) { }
        if (portOpen(host, GoogleTvDriver.PAIR_PORT, 1500)) return new Tv(host, "googletv", "TV at " + host, "");
        if (portOpen(host, 8002, 800) || portOpen(host, 8001, 800)) return new Tv(host, "samsung", "Samsung TV", "");
        if (portOpen(host, 3000, 800) || portOpen(host, 3001, 800)) return new Tv(host, "lg", "LG TV", "");
        return null;
    }
}
