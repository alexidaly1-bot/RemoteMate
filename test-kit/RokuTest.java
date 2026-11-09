import com.remotemate.*;
import java.util.*;

/** Finds the fake Roku with SSDP, "pairs" (no code), sends buttons, apps, typing and voice search. Run with fakeroku.py. */
public class RokuTest {
  public static void main(String[] a) throws Exception {
    boolean blocked = a.length > 0 && a[0].equals("blocked");
    TvDriver.Listener L = new TvDriver.Listener() {
      public void onState(String s, String d) { System.out.println("STATE " + s + " " + d); }
      public void onVolume(int l, int m, boolean mu) { }
      public void onTextField(boolean s, String l) { }
      public void onPower(boolean on) { System.out.println("POWER " + on); }
    };
    if (blocked) {
      RokuDriver r = new RokuDriver(); r.setListener(L);
      try { r.pairStart("127.0.0.1", "Test phone"); System.out.println("BLOCKED ROKU ACCEPTED?!"); }
      catch (Exception e) { System.out.println("blocked message: " + e.getMessage()); }
      System.exit(0);
    }
    // 1. discovery (SSDP to the fake responder on 127.0.0.1:1901)
    List<Ssdp.Tv> found = Collections.synchronizedList(new ArrayList<>());
    new Ssdp("127.0.0.1", 1901).search(3500, tv -> { System.out.println("FOUND " + tv + " model=" + tv.model); found.add(tv); });
    Thread.sleep(1500);
    // 2. typed-in address
    System.out.println("PROBE " + Ssdp.probe("127.0.0.1"));
    // 3. pair (no code on Roku) and connect
    RokuDriver r = new RokuDriver(); r.setListener(L);
    System.out.println("brand=" + r.brand() + " pairing=" + r.pairing() + " buttons=" + r.supportedNames().size());
    r.pairStart("127.0.0.1", "Test phone");
    System.out.println("paired, name=" + r.deviceName());
    r.setHost("127.0.0.1"); r.connect();
    for (int i = 0; i < 30 && !r.state().equals("connected"); i++) Thread.sleep(100);
    System.out.println("state " + r.state());
    // 4. buttons, long press, unsupported button ignored
    r.press(Button.HOME, Button.SHORT);
    r.press(Button.VOL_UP, Button.SHORT);
    r.press(Button.OK, Button.START_LONG); r.press(Button.OK, Button.END_LONG);
    r.press(Button.HDMI2, Button.SHORT); r.press(Button.NUM_5, Button.SHORT); r.press(Button.REPLAY, Button.SHORT);
    System.out.println("supports PROFILE=" + r.supports(Button.PROFILE));
    r.press(Button.PROFILE, Button.SHORT); // should send nothing
    // 5. apps, typing, voice search
    for (TvDriver.App x : r.apps()) System.out.println("APP " + x.name + " = " + x.ref);
    r.launch("837");
    r.text("Bluey 2");
    r.search("bluey season 3");
    Thread.sleep(1500);
    System.exit(0);
  }
}
