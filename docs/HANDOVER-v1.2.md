# TV Remote — Handover Pack

Everything used to build Alex's TV Remote app (v1.2), written so you (or another AI coding tool) can turn it into a **universal TV remote app** for the Play Store.

---

## 1. What the app does today

- Controls a **Google TV / Android TV** (tested design target: **Kogan G6, Android TV OS 14**) over **Wi-Fi**. No IR, no hub, no Google account.
- **Phone (Galaxy Z Fold6):** a thin bar on the right edge. Swipe it and a remote slides in over any app. Fixed pixel size, measured once on the unfolded screen and kept when folded.
- **Chromebook / window mode:** the same remote as a normal resizable window. The keyboard works as a remote.
- **Features:** every button on the physical Kogan remote, a touchpad (swipe = arrows, tap = OK, hold = long OK), real **voice search** (phone mic streamed to the TV), **typing** into TV text boxes, 3 app shortcut buttons (SmartTube, Prime Video, BrowseHere), live volume readout, auto-reconnect, quick settings tile.
- **Size:** about 90 KB. No third-party libraries.

**Testing status:** the protocol was tested end-to-end against a fake TV built from the official message definitions (pairing, wrong code rejected, keys, long press, app launch, typing, voice). Real-TV results: confirm on your own TV.

---

## 2. File map

```
source/
  settings.gradle, build.gradle, gradle.properties   Gradle project (AGP 8.7.3, compileSdk 35, minSdk 33)
  tvremote.jks                                        Signing key for YOUR installed copy (not included here; stays with the personal app)
  app/build.gradle                                    App id au.alex.tvremote, version 1.2
  app/src/main/AndroidManifest.xml                    Permissions, services, tile, window activity
  app/src/main/java/au/alex/tvremote/
    Proto.java          Tiny protobuf writer/reader (no library needed)
    Certs.java          Makes the phone's RSA key + self-signed certificate; TLS setup
    AtvClient.java      THE PROTOCOL: pairing (port 6467) + remote control (port 6466) + voice + typing
    RemoteService.java  Edge bar + slide-in overlay panel (phone)
    RemoteActivity.java Remote as a normal window (Chromebook) + keyboard shortcuts
    MainActivity.java   Setup screen: permissions, find TV on Wi-Fi, pair with code
    RemoteTile.java     Quick settings tile (show/hide remote)
  app/src/main/assets/
    panel.html          THE REMOTE UI (layout, buttons, touchpad, voice, typing, menu)
    setup.html          Setup screen UI
  app/src/main/res/     Icons and theme
protocol/
  polo.proto            Pairing messages (from androidtvremote2, Apache-2.0)
  remotemessage.proto   Remote-control messages + every key code (305 keys)
  androidtvremote2-LICENSE.txt
test-kit/
  faketv.py             A fake Google TV for testing without a real TV
  Test.java, TestHook.java  Java test that pairs and sends every message type
design/
  Alex's mockups and the app-vs-mockup comparisons
```

**Build:**
```
sdk.dir=/path/to/android-sdk      (in local.properties)
gradle assembleRelease            → app/build/outputs/apk/release/app-release.apk
```

---

## 3. How the Google TV protocol works

This is the same protocol the official Google TV phone app uses. Protocol details were learned from the open-source Python library **androidtvremote2** (Apache-2.0, by tronikos). The Java code here is original, but credit that library in your app's licences screen.

### 3.1 Finding TVs
- mDNS / DNS-SD service type: **`_androidtvremote2._tcp`** (done with Android's `NsdManager`).
- Hold a `WifiManager.MulticastLock` while searching, or Samsung phones miss replies.
- ChromeOS's Android container often **can't see mDNS**. Keep a "type the IP address" box.

### 3.2 Identity
- The phone makes **one RSA-2048 key + self-signed X.509 certificate** and keeps it forever (`files/remote-key.p12`).
- The certificate copies the library's: CN=`atvremote`, **basicConstraints CA:true, pathlen 0**, **subjectAltName DNS:atvremote**. Without these extensions some TLS stacks reject it.
- The TV uses its own self-signed cert, so the app trusts any server cert. Pairing with the on-screen code is what proves it's the right TV.
- **Losing the key = re-pair.** Back it up if you add phone-to-phone sync.

### 3.3 Pairing (TLS, port 6467)
Every message is a **varint length prefix + protobuf `OuterMessage`** with `protocol_version=2`, `status=200`.

1. Phone → `pairing_request` (field 10): service_name `atvremote`, client_name e.g. "Galaxy Z Fold6 remote"
2. TV → `pairing_request_ack` (11)
3. Phone → `options` (20): input encoding **HEXADECIMAL (3), 6 characters**, preferred role INPUT (1)
4. TV → `options` (20)
5. Phone → `configuration` (30): same encoding, client_role INPUT
6. TV → `configuration_ack` (31). **The TV now shows a 6-character code.**
7. Phone → `secret` (40): `SHA-256( phoneModulus ‖ phoneExponent ‖ tvModulus ‖ tvExponent ‖ hexbytes(code[2..6]) )`
   - Numbers are unsigned big-endian **with no leading zero byte** (exponent 65537 = `01 00 01`).
   - **Check:** the first byte of the hash must equal `code[0..2]`. If not, the user typed it wrong. Reject before sending.
8. TV → `secret_ack` (41). Paired. A wrong code gives status **402**.

### 3.4 Remote control (TLS, port 6466, same client cert)
Messages are length-prefixed protobuf `RemoteMessage`. Field numbers:

| Field | Message | Who sends | What to do |
|---|---|---|---|
| 1 | remote_configure | TV first, then phone | Reply with features + device info (see below) |
| 2 | remote_set_active | TV, then phone | Echo `active = features` |
| 8 / 9 | ping request / response | TV every ~5 s | Reply with the same `val1`, or the TV drops you |
| 10 | remote_key_inject | phone | `key_code`, `direction` (1 start long, 2 end long, 3 short) |
| 21 | remote_ime_batch_edit | both | Typing. Keep `ime_counter` and `field_counter` from the TV |
| 22 | remote_ime_show_request | TV | A text box is focused → pop up the keyboard |
| 30 / 31 / 32 | voice begin / payload / end | both | See voice |
| 40 | remote_start | TV | `started` = TV is on |
| 50 | remote_set_volume_level | TV | Volume level/max/muted for the on-screen readout |
| 90 | remote_app_link_launch_request | phone | Open an app (see 3.6) |

**Features bitmask the app asks for:** PING 1, KEY 2, IME 4, VOICE 8, POWER 32, VOLUME 64, APP_LINK 512 → **623**.
Reply to `remote_configure` with `code1 = features & tvFeatures` and device info `{unknown1: 1, unknown2: "1", package_name: "atvremote", app_version: "1.0.0"}`.

**Unpaired phone:** the TV accepts TLS, then closes immediately (EOF) or fails the handshake. Treat both as **"needs pairing"**.

### 3.5 Key codes used (Android `KeyEvent` numbers)
Power 26 · Mute 164 · Vol+ 24 · Vol− 25 · Input 178 · Ch+ 166 · Ch− 167 · Rewind 89 · Play/Pause 85 · FFwd 90 · Profile 288 · Settings 176 · Search/Voice 84 · Up 19 · Down 20 · Left 21 · Right 22 · OK 23 · Back 4 · Home 3 · Live TV 170 · Delete 67 · Enter 66.
All 305 codes are in `protocol/remotemessage.proto`.

**Repeat:** volume, channel and seek repeat every 140 ms after a 420 ms hold. Other buttons send start-long/end-long when held 550 ms.

### 3.6 Opening apps
Send field 90 with a link:
- `market://launch?id=<package>` opens an installed app by package name (most reliable)
- A web link (`https://www.netflix.com/title`, `https://www.youtube.com`) opens the app that owns it

Packages used: SmartTube `com.teamsmart.videomanager.tv` (beta: `com.liskovsoft.smarttubetv.beta`) · Prime Video TV `com.amazon.amazonvideo.livingroom` · BrowseHere `com.tcl.browser`.
**Tip for the universal app:** ask the TV which apps are installed (not in this protocol). Use ADB, or let the user pick from a list of common packages.

### 3.7 Voice
- Phone sends key **84 (SEARCH)**, short. TV replies `voice_begin` (30) with `session_id`. Phone echoes `voice_begin`.
- Stream **16-bit PCM, mono, 8000 Hz** in `voice_payload` (31) chunks of **8–20 KB** (pad small chunks to 8 KB).
- Stop after ~1.3 s of silence (RMS < 900), 6 s of no speech, or 15 s max. Send `voice_end` (32).
- Needs the VOICE feature. If no `voice_begin` arrives within 2.5 s, the TV doesn't support it → fall back to typing.

### 3.8 Typing
`remote_ime_batch_edit` (21): `ime_counter`, `field_counter`, `edit_info { insert: 1, text_field_status { start, end, value } }`. The app sends the whole text in one go, then Enter (66). Delete (67) for backspace.

---

## 4. Phone UI tricks (Android)

- **Overlay panel:** `TYPE_APPLICATION_OVERLAY` window from a **foreground service** (`specialUse`, plus `microphone` when mic permission is granted). Needs "Display over other apps".
- **Use a window context** (`createWindowContext`) so the panel follows fold/unfold correctly.
- **Fixed size across fold/unfold:** measure once when the screen is ≥ 1600 px wide (26.2% of width × 87% of height), save it, and reuse it when folded.
- **Edge bar:** 46 × 300 px window at the right edge. Swipe left or tap = open. Hold 0.5 s then drag = move it (position saved as a fraction of screen height).
- **Typing in an overlay:** remove `FLAG_NOT_FOCUSABLE` only while the type box is open, or the keyboard won't appear.
- **Samsung's Edge Panel can't be used by third-party apps** to host this, so the app makes its own edge bar.
- **Window mode (Chromebook):** a normal Activity with `<layout defaultWidth=340dp defaultHeight=860dp>`. The page scales the 300 px-wide remote to fit using CSS `zoom`.
- **Disconnect** 90 s after the panel is hidden to save battery.

---

## 5. Lessons learned

1. **Downloads got cut off** at 48 MB on Alex's phone/Chromebook. Keep the APK small; this one is 90 KB.
2. **Chromebook install:** enable ADB debugging → `adb connect 100.115.92.2:5555` → `adb -s 100.115.92.2:5555 install -r app.apk`. `adb connect arc` makes a duplicate device, so use the IP.
3. **The remote image on the buttons** uses coloured text, not real logos (trademark reasons).
4. Alex's preferred layout is in `design/` and is what `panel.html` now matches.

---

## 6. Turning it into a universal app

### 6.1 Goal (Alex's words, tidied)
Anyone with a smart TV downloads the app and instantly gets a remote that matches their TV. Pairing is as quick as possible. They can **rearrange, add or remove buttons**, like Alex's layout. It's a ready-made backup if they lose the real remote, and easier than Android's built-in one (no swipe down → tap → pair).

### 6.2 Recommended setup flow (fastest possible)
1. **Open app → it searches the Wi-Fi straight away for every TV brand at once** (mDNS + SSDP). Most people never need a photo.
2. Show found TVs as cards with the brand name. **One tap** on their TV.
3. Pair the way that brand allows:
   - Google TV / Android TV: 6-character code on the TV (can't be skipped). Auto-focus the box and auto-submit at 6 characters, as this app does.
   - Samsung, LG: just press **Allow** on the TV.
   - Roku: **no pairing at all**.
4. Load that brand's **preset layout**, which the user can then edit.
5. **Photo option (fallback / extra):** "Can't find your TV? Take a photo of its About/Settings screen or the sticker on the back." Use on-device OCR (ML Kit) to read the brand, OS and model, then pick the right protocol and layout. Optionally a **photo of their physical remote** → an AI vision model suggests a matching button layout.

### 6.3 Which TVs can be supported over Wi-Fi
Verify each against the named open-source library before building. These are the libraries Home Assistant uses, and they're the best current reference.

| Platform | Brands | How | Pairing | Reference library |
|---|---|---|---|---|
| **Google TV / Android TV** | Sony, TCL, Hisense (Google TV), Philips (Android), Kogan, Xiaomi, Chromecast w/ Google TV, Nvidia Shield | This app's protocol (ports 6466/6467) | 6-char code on TV | androidtvremote2 |
| **Samsung Tizen** (2016+) | Samsung | WebSocket on 8002 (wss), apps via REST on 8001 | "Allow" on TV → token | samsungtvws |
| **LG webOS** | LG | WebSocket SSAP on 3000/3001 + pointer socket for buttons | "Allow" on TV → client key | aiowebostv |
| **Roku OS** | Roku, TCL Roku, Hisense Roku | HTTP "ECP" on port 8060 (`/keypress/Home`, `/launch/<id>`) | None | rokuecp |
| **Fire TV** | Amazon, Fire TV Edition TVs | ADB over network (user turns on ADB debugging) | "Allow USB debugging?" on TV | androidtv (ADB) |
| **Vizio SmartCast** | Vizio | HTTPS on 7345 (older 9000) | 4-digit PIN | pyvizio |
| **Sony Bravia (older, non-Google)** | Sony | IRCC-IP (SOAP) | PIN or pre-shared key | pybravia |
| **Philips (Saphi/JointSpace)** | Philips non-Android | HTTP 1925/1926 | PIN | ha-philipsjs |
| **Hisense VIDAA** | Hisense, some Toshiba | MQTT (undocumented) | Code on TV | community projects only. Risky |
| **Apple TV** | Apple | Companion/MRP | PIN | pyatv |

**Best order to build:** Google TV (done) → Roku (easiest) → Samsung → LG → Fire TV → the rest.
**No Wi-Fi control?** Only an IR blaster works, and the Fold6 has none, so list those TVs as "not supported".

### 6.4 Code changes needed
1. **Split the protocol behind one interface:**
   `TvDriver { discover(), pair(), connect(), key(Button), launch(App), text(), voice() }` with one driver per platform. `AtvClient` becomes `GoogleTvDriver`.
2. **Generic buttons** (`POWER, VOL_UP, OK, HOME…`) mapped to each brand's codes in a table, so the layout never cares which TV it is.
3. **Layout as data, not HTML.** Move `panel.html`'s hard-coded rows to JSON, e.g.:
   ```json
   {"rows":[
     {"type":"keys","items":["MUTE","grip","POWER"]},
     {"type":"apps","items":[{"name":"SmartTube","link":"market://launch?id=com.teamsmart.videomanager.tv"}]},
     {"type":"grid","cols":3,"items":["INPUT","PROFILE","CH_UP","USB","PLAY_PAUSE","CH_DOWN","MIC",{"rocker":"VOLUME"},"LIVE_TV","REWIND","FFWD"]},
     {"type":"touchpad"},
     {"type":"keys","items":["BACK","HOME","SETTINGS"]}]}
   ```
   Then build an **editor:** hold a button → drag to move, ✕ to remove, + to add from a list. Include presets per brand plus "Alex's layout".
4. **Multiple TVs:** save several paired TVs with a quick switcher.
5. **Settings:** button size, haptics, dark/medium/light themes (reuse Fold Files' colour sets), which side the edge bar sits on.

### 6.5 Play Store checklist
- **New app ID and new signing key** (don't reuse `au.alex.tvremote` / `tvremote.jks`, which are for your personal copy). Use Play App Signing.
- Don't use **"Google TV", "Android TV", Netflix, YouTube or Prime logos** in the name, icon or screenshots. Text app names on buttons are fine.
- **Permissions to justify:** Display over other apps (core feature), Microphone (voice search, needs a privacy policy), foreground service "special use" (declare it in Play Console with a short video).
- **Privacy policy:** say voice audio goes straight from the phone to the user's TV on their home network. Nothing goes to your servers.
- **Credit** androidtvremote2 (Apache-2.0) and any other libraries in an "Open-source licences" screen.
- **Free + subscription idea** (fits your app studio plan): free = one TV with the standard layout. Paid = custom layouts, multiple TVs, edge panel, widgets, voice.

---

## 7. Test kit

`test-kit/faketv.py` pretends to be a Google TV on 127.0.0.1 (ports 6466/6467). It checks every message against the real protocol definitions, prints the pairing code to `code.txt`, and logs what it receives.
Needs Python 3 with `protobuf` and `cryptography`, plus `tv.crt` / `tv.key` (`openssl req -x509 -newkey rsa:2048 -nodes -keyout tv.key -out tv.crt -subj /CN=faketv`) and the androidtvremote2 source folder path as the first argument (it reads the `_pb2.py` files as data only).
`Test.java` pairs (and checks a wrong code is rejected), connects, then sends keys, a long press, an app link, text and a voice clip.
