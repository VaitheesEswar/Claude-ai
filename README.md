# AC Remote – Wi-Fi remote for Blue Star Smart ACs (Android)

An Android app (Kotlin + Jetpack Compose) that controls Blue Star Wi-Fi air
conditioners over the internet, using the same Blue Star cloud as the official
**Blue Star Smart** app.

> **Unofficial.** Not affiliated with or endorsed by Blue Star Ltd. Blue Star has
> no public API; this app uses the protocol of their Android app as documented by
> the community. If Blue Star changes their servers, the app can stop working.

## Features

The main screen looks like a real AC remote: an LCD-style display (mode, set temperature,
fan level, swing, room temperature, Wi-Fi status) and round buttons for **Power**, **Mode**,
**Fan**, **Swing** and **Temp ▲/▼**. The colours follow the mode (blue Cool, teal Dry,
green Fan, purple Auto).

- Sign in with your Blue Star Smart account (mobile number or email)
- Power on/off
- Mode: Cool, Dry, Fan, Auto
- Set temperature 16–30 °C (1 °C steps)
- Fan speed: Auto, Low, Medium, High, Turbo (fixed automatically in Dry/Auto, as the AC requires)
- Vertical swing: Off, Swing, or fixed angle 1–5; horizontal (left-right) swing on/off
- Sleep, Turbo, Eco (capacity 110%–40%), indoor display on/off, self clean, buzzer on/off
- Timer: turn off in 30 min–8 h, turn off at a time, or turn on at a time (once or every day).
  The phone sends the command at that time, even with the app closed, and shows a notification
- Shows online/offline, room temperature and AC fault codes (refreshes every 10 s while open)
- Several ACs on one account: pick one from the title bar

## Get the APK

**Direct download (latest build):**
https://github.com/VaitheesEswar/Claude-ai/releases/download/latest-apk/AC-Remote.apk

Open that link on your phone (logged in to GitHub if the repo is private), then tap the
downloaded `AC-Remote.apk` to install. Allow "Install unknown apps" for your browser when
asked. Needs Android 8.0 or newer. Every push rebuilds it and refreshes the
**latest-apk** release on the repo's Releases page.

### Build it yourself

Needs Android Studio (or JDK 17 + Android SDK 35):

```bash
./gradlew :core:test          # protocol + signing unit tests (no Android SDK needed)
./gradlew :app:assembleDebug  # -> app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press **Run**.

## Using it

1. Make sure the AC already works in the official Blue Star Smart app (Wi-Fi setup
   is done there).
2. Open AC Remote, choose **Mobile number** or **Email**, and sign in with the same
   account. Mobile numbers are 10 digits, without `+91`.
3. Control the AC. Changes usually reach the unit within a second or two.

The phone does not need to be on the same Wi-Fi as the AC, since commands go via the cloud.

## How it works

```
App ──HTTPS──▶ api-smart.bluestarindia.com   POST /auth/login  → session + AWS IoT keys
                                              GET  /things      → your ACs + their state
App ──HTTPS (AWS SigV4)──▶ AWS IoT Core       POST /things/{id}/shadow
                                              {"state":{"desired":{...}}}  → AC applies it
```

| Path | What's in it |
| --- | --- |
| `core/` | Pure Kotlin, no Android: SigV4 signer, command builder, state parser, unit tests |
| `app/src/main/java/.../data/BlueStarClient.kt` | Login, device list, sending commands |
| `app/src/main/java/.../data/CredentialStore.kt` | Login stored on the phone, password encrypted with an Android Keystore key |
| `app/src/main/java/.../ui/` | ViewModel and Compose screens |

Command fields (from the firmware): `pow` (0/1), `mode` {value 0 fan, 2 cool, 3 dry, 4 auto},
`stemp` ("24.0"), `fspd` (2 low, 3 med, 4 high, 6 turbo, 7 auto), `vswing` (6 off, 0 swing,
1–5 angles), plus `lcmd` (the field changed), `src` and `ts`.

## Signing

All builds are signed with one fixed key (`app/signing/acremote.p12`), so a new APK installs
over the old one and keeps your login. The key is only for this personal app: anyone with
access to the repo could sign an APK as it, so don't reuse it for apps you publish.

## Privacy & security

- Your password is stored only on your phone, encrypted with a key held in the Android
  Keystore, so the app can sign in again when the session expires. **Sign out** deletes it.
- It is sent only to Blue Star's server. Cleartext HTTP is disabled app-wide.
- App backups are disabled so the login data isn't copied off the device.

## Credits

Protocol details (endpoints, field codes, fault-code table) come from the MIT-licensed
Home Assistant integration [ArjunVRa/bluestar_smartac](https://github.com/ArjunVRa/bluestar_smartac).
