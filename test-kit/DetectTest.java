import com.remotemate.BrandDetector;

/** Checks the photo reader on text like ML Kit returns from About screens and stickers. */
public class DetectTest {
  static int fails = 0;
  static void check(String name, String text, String platform, String ip) {
    BrandDetector.Result r = BrandDetector.read(text);
    boolean ok = r.platform.equals(platform) && r.ip.equals(ip);
    if (!ok) fails++;
    System.out.println((ok ? "PASS " : "FAIL ") + name + " → platform=" + r.platform + " label=\"" + r.label + "\" ip=" + r.ip + " model=" + r.model);
  }
  public static void main(String[] a) {
    check("Kogan Google TV about", "About\nModel\nKogan G6\nAndroid TV OS version\n14\nNetwork\nIP address 192.168.1.23\n", "googletv", "192.168.1.23");
    check("Roku about", "Settings > System > About\nTCL Roku TV\nModel: 55R646\nSoftware version 12.5.0\nIP address\n10.0.0.48", "roku", "10.0.0.48");
    check("Samsung sticker", "SAMSUNG\nModel Code: QA55Q60CAWXXY\nSerial No.\n220-240V~ 50/60Hz", "samsung", "");
    check("LG webOS", "LG\nwebOS TV Version 23\nModel: OLED55C3PSA", "lg", "");
    check("Fire TV", "My Fire TV\nAbout\nFire OS 7.6.6.9\nNetwork IP 192.168.0.77", "firetv", "192.168.0.77");
    check("Hisense VIDAA", "Hisense\nVIDAA U7.6\nModel 65A7H", "hisense", "");
    check("Sony Google TV, OCR comma", "SONY BRAVIA\nGoogle TV\nIP 192,168,1,40", "googletv", "192.168.1.40");
    check("Public IP ignored", "TCL\nAndroid TV\nDNS 8.8.8.8", "googletv", "");
    check("Nothing readable", "blurry text here", "", "");
    System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
    System.exit(fails == 0 ? 0 : 1);
  }
}
