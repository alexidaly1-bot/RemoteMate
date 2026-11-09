package com.remotemate;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads text from a photo of the TV's About screen (or the sticker on the back) and works out
 * which platform it runs, so the app knows how to talk to it. Text comes from on-device OCR.
 */
public final class BrandDetector {
    private BrandDetector() {}

    public static final class Result {
        /** googletv, roku, samsung, lg, firetv, vizio, hisense, philips, sony, other, or "" if nothing found. */
        public final String platform;
        /** What we'd tell the user, e.g. "Kogan (Google TV)". */
        public final String label;
        /** An IP address seen in the photo (About/Network screens often show it), or "". */
        public final String ip;
        public final String model;
        Result(String platform, String label, String ip, String model) { this.platform = platform; this.label = label; this.ip = ip; this.model = model; }
    }

    // OS words decide the protocol. Checked in this order.
    private static final String[][] OS = {
            {"roku", "roku"},
            {"googletv", "google tv"}, {"googletv", "android tv"}, {"googletv", "androidtv"}, {"googletv", "chromecast"},
            {"googletv", "android os"}, {"googletv", "android version"},
            {"firetv", "fire tv"}, {"firetv", "fire os"}, {"firetv", "fireos"},
            {"samsung", "tizen"}, {"lg", "webos"}, {"lg", "web os"},
            {"vizio", "smartcast"}, {"hisense", "vidaa"}, {"philips", "saphi"},
    };
    // Brand words, with the platform their smart TVs usually run when no OS word is visible.
    private static final String[][] BRANDS = {
            {"Samsung", "samsung"}, {"LG", "lg"}, {"Roku", "roku"}, {"Amazon", "firetv"}, {"Vizio", "vizio"},
            {"Sony", "googletv"}, {"Bravia", "googletv"}, {"TCL", "googletv"}, {"Kogan", "googletv"}, {"Hisense", "hisense"},
            {"Philips", "googletv"}, {"Xiaomi", "googletv"}, {"Nvidia", "googletv"}, {"Shield", "googletv"}, {"Panasonic", "other"},
            {"Toshiba", "other"}, {"Sharp", "googletv"}, {"Ffalcon", "googletv"}, {"Polaroid", "other"}, {"JVC", "other"},
    };

    static final Pattern IP = Pattern.compile("\\b((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\s?[.,]\\s?(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})\\b");
    static final Pattern MODEL = Pattern.compile("(?i)model(?:\\s*(?:name|number|code|no\\.?))?\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9\\-_.]{3,})");

    public static Result read(String text) {
        if (text == null) text = "";
        String low = " " + text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        String platform = "", os = "";
        for (String[] o : OS) if (low.contains(o[1])) { platform = o[0]; os = o[1]; break; }
        String brand = "";
        for (String[] b : BRANDS) {
            if (Pattern.compile("(?i)(?<![a-z])" + Pattern.quote(b[0]) + "(?![a-z])").matcher(text).find()) {
                brand = b[0];
                if (platform.isEmpty()) platform = b[1];
                break;
            }
        }
        String ip = "";
        Matcher m = IP.matcher(text);
        while (m.find()) {
            String c = m.group(1).replaceAll("\\s", "").replace(',', '.');
            if (isLocal(c)) { ip = c; break; }
        }
        String model = "";
        Matcher mm = MODEL.matcher(text);
        if (mm.find()) model = mm.group(1);
        String nice = niceOs(platform);
        String label = brand.isEmpty() ? nice
                : os.isEmpty() || nice.toLowerCase(Locale.ROOT).startsWith(brand.toLowerCase(Locale.ROOT)) ? (os.isEmpty() ? brand : nice)
                : brand + " (" + nice + ")";
        return new Result(platform, label, ip, model);
    }

    /** Home Wi-Fi addresses only: 192.168.x.x, 10.x.x.x, 172.16–31.x.x. */
    static boolean isLocal(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4) return false;
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return (a == 192 && b == 168) || a == 10 || (a == 172 && b >= 16 && b <= 31);
    }

    public static String niceOs(String platform) {
        switch (platform) {
            case "googletv": return "Google TV";
            case "roku": return "Roku";
            case "samsung": return "Samsung";
            case "lg": return "LG webOS";
            case "firetv": return "Fire TV";
            case "vizio": return "Vizio";
            case "hisense": return "Hisense VIDAA";
            case "philips": return "Philips";
            case "sony": return "Sony";
            case "other": return "a TV we don't support yet";
            default: return "";
        }
    }

    /** Brands this version can control. */
    public static boolean supported(String platform) { return platform.equals("googletv") || platform.equals("roku"); }
}
