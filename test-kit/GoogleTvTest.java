import com.remotemate.*;
import java.io.File;
import java.nio.file.*;
/** Pairs with the fake Google TV (faketv.py), checks a wrong code is rejected, then sends every message type. */
public class GoogleTvTest {
  static String code() throws Exception { return new String(Files.readAllBytes(Paths.get("code.txt"))).trim(); }
  public static void main(String[] a) throws Exception {
    File kf=new File("client.p12"); kf.delete(); new File("tv-pins.properties").delete();
    TestHook.make(kf);
    TvDriver.Listener L=new TvDriver.Listener(){
      public void onState(String s,String d){System.out.println("STATE "+s+" "+d);}
      public void onVolume(int l,int m,boolean mu){System.out.println("VOL "+l+"/"+m+" "+mu);}
      public void onTextField(boolean s,String l){System.out.println("TEXTFIELD "+l);}
      public void onPower(boolean on){System.out.println("POWER "+on);}
    };
    GoogleTvDriver c=new GoogleTvDriver(kf); c.setListener(L);
    System.out.println("brand="+c.brand()+" pairing="+c.pairing()+" buttons="+c.supportedNames().size());
    // unpaired connect should say needs_pairing
    c.setHost("127.0.0.1"); c.connect(); Thread.sleep(1500); System.out.println("before pairing: "+c.state());
    c.disconnect();
    // wrong code is rejected
    c.pairStart("127.0.0.1","Test phone"); Thread.sleep(300);
    try { c.pairFinish("000000"); System.out.println("BAD CODE ACCEPTED?!"); } catch(Exception e){System.out.println("wrong code rejected: "+e.getMessage());}
    // right code (typed in lower case) is accepted
    c.pairStart("127.0.0.1","Test phone"); Thread.sleep(300); String code=code();
    c.pairFinish(code.toLowerCase()); System.out.println("paired with "+code);
    // a new driver instance reloads the same key and connects
    GoogleTvDriver c2=new GoogleTvDriver(kf); c2.setListener(L); c2.setHost("127.0.0.1"); c2.connect();
    for(int i=0;i<30&&!c2.state().equals("connected");i++)Thread.sleep(100);
    Thread.sleep(300);
    System.out.println("after pairing: "+c2.state());
    // generic buttons → Android key codes
    c2.press(Button.VOL_UP,Button.SHORT); Thread.sleep(50);
    c2.press(Button.OK,Button.START_LONG); Thread.sleep(50); c2.press(Button.OK,Button.END_LONG); Thread.sleep(50);
    c2.press(Button.HOME,Button.SHORT); Thread.sleep(50); c2.press(Button.USB,Button.SHORT); Thread.sleep(50);
    c2.press(Button.HDMI2,Button.SHORT); Thread.sleep(50); c2.press(Button.NUM_7,Button.SHORT); Thread.sleep(50);
    c2.launch("market://launch?id=com.teamsmart.videomanager.tv"); Thread.sleep(50);
    c2.text("bluey"); Thread.sleep(400);
    System.out.println("voiceStream="+c2.voiceStream());
    int sid=c2.voiceStart(); System.out.println("voice session "+sid);
    c2.voiceChunk(sid,new byte[30000],30000); c2.voiceChunk(sid,new byte[100],100); c2.voiceEnd(sid);
    Thread.sleep(800);
    // a different device on the TV's address is refused (certificate pinned at pairing)
    c2.disconnect();
    java.util.Properties pins=new java.util.Properties(); pins.load(new java.io.FileInputStream("tv-pins.properties"));
    System.out.println("pinned: "+pins.stringPropertyNames());
    pins.setProperty("127.0.0.1","0000"); pins.store(new java.io.FileOutputStream("tv-pins.properties"),null);
    GoogleTvDriver c3=new GoogleTvDriver(kf); c3.setListener(L); c3.setHost("127.0.0.1"); c3.connect();
    for(int i=0;i<30&&c3.state().equals("connecting");i++)Thread.sleep(100);
    System.out.println("with wrong pin: "+c3.state());
    System.exit(0);
  }
}
