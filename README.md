# RemoteMate

A universal TV remote for Android phones and Chromebooks. Works over Wi-Fi. Version 1.0.

- **Google TV / Android TV**: pairs with the 6-character code on the TV. Buttons, touchpad, voice (mic streamed to the TV), typing, app shortcuts.
- **Roku**: no pairing. Buttons, touchpad, typing, app shortcuts, voice (phone hears you, Roku runs the search).
- **Samsung, LG, Fire TV**: found on the Wi-Fi and shown as "coming in a later version".
- Opens by searching the Wi-Fi for every kind of TV at once. Can't find it? Take a photo of the TV's About screen and the phone reads the brand (on-device).
- Button editor: hold to move, ✕ to remove, + to add. Layouts are JSON: a preset per brand, Alex's layout, and "My layout".
- Edge bar + slide-in panel on phones, resizable window on Chromebooks. Dark, thin line icons. APK about 1.2 MB.

Started from Alex's personal TV Remote v1.2 (`au.alex.tvremote`). This is a separate app with its own ID (`com.remotemate.app`) and signing key.

## Build

```
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

Release signing reads `keystore.properties` (not in git):

```
storeFile=signing/remotemate-upload.jks
storePassword=…
keyAlias=remotemate
keyPassword=…
```

Without it the release APK is built unsigned.

## How it fits together

| File | What it does |
|---|---|
| `Button.java` | Generic buttons (`POWER`, `VOL_UP`, `OK`, `HDMI1`…). Layouts only name these. |
| `TvDriver.java` | One interface for every TV brand: pair, connect, press, launch, text, voice. |
| `GoogleTvDriver.java` | Google TV protocol (ports 6466/6467). The v1.2 `AtvClient`, plus certificate pinning. |
| `RokuDriver.java` | Roku ECP (HTTP port 8060). |
| `Ssdp.java`, `Finder.java` | Wi-Fi search: mDNS (Google TV, Fire TV) + SSDP (Roku, Samsung, LG, others). |
| `BrandDetector.java` | Reads brand, OS and IP address from photo text. |
| `Tvs.java` | Saved TVs, current driver, layouts. |
| `assets/panel.html` | The remote. Renders a layout JSON; includes the editor. |
| `assets/setup.html` | Find, pair, photo fallback, permissions. |
| `assets/layouts/*.json` | Presets: `googletv`, `roku`, `alex`. |

**Adding a brand:** write `XxxDriver extends TvDriver`, map `Button`s to its codes, add it to `Tvs.make()` and `BrandDetector.supported()`, add `assets/layouts/xxx.json`.

## Tests

`test-kit/run.sh` runs the drivers against a fake Google TV (`faketv.py`) and a fake Roku (`fakeroku.py`), and checks the photo reader.
`node test-kit/ui-test.js` tests the remote, editor and setup pages in headless Chromium.

## Credits

Google TV protocol details from [androidtvremote2](https://github.com/tronikos/androidtvremote2) (Apache-2.0). See `docs/protocol/`.
