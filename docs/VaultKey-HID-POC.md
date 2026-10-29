# VaultKey Bluetooth HID prototype (Android / K20 Pro)

## Current milestone: test a harmless Bluetooth HID keyboard

This development branch contains a **debug-only**, separate launcher Activity named **VaultKey HID Test**. It does not read the vault and **cannot send real vault passwords** yet. The purpose is to verify Bluetooth HID hardware compatibility and host typing accuracy.

Implemented:
- Bluetooth HID Device registration through Android public APIs.
- `BLUETOOTH_CONNECT` runtime permission requested by the debug-only test Activity.
- Manual list of already paired/bonded devices and explicit Connect button.
- Separate **single-letter probe** (`a`) and full test message, each with explicit confirmation.
- US-layout ASCII keycode mapping (including Tab/Enter), with press/release pacing.
- Cancellation handling / best-effort key release, and app cleanup.
- Report encoding unit tests.
- Independent GitHub Actions workflow `VaultKey Debug CI` that builds `standardDebug`.

The debug Activity uses the classic Android UI and is launched from a separate home-screen icon. Production release variants have neither this Activity nor its Bluetooth permission.

## K20 Pro hardware test steps

1. Go to the development PR's latest **VaultKey Debug CI** run. Download the **vaultkey-standard-debug-apk** artifact from the successful run, then unzip the artifact and install the APK on the K20 Pro. **Do not install a failed/partial build.**
2. **Important after the V0.2 mouse-cursor bug:** completely forget/remove the existing K20 Pro ↔ PC/Mac Bluetooth pairing on **both devices**, then pair again. The original version incorrectly advertised a keyboard+mouse combo although it transmitted keyboard-only reports; some hosts may retain the old HID SDP descriptor in their pairing cache. Power-cycle Bluetooth on both devices if removal alone does not refresh the device profile. Pair again only after the newly built APK is installed and the HID test activity is started. On the target, open a plain text editor, select a **US** keyboard layout, and focus a blank document.
3. Launch **VaultKey HID Test** (a separate icon from the regular Bitwarden app).
4. Tap **1. Start Bluetooth HID** and approve Android's Nearby devices permission. Wait until `HID ready`.
5. Tap **2. Refresh paired devices**, choose the correct destination in the list.
6. Tap **3. Connect selected device**, then wait for `Connected`. Some host Bluetooth stacks require connecting from the PC side; if it fails, inspect Bluetooth Settings on the target.
7. Tap **4. Send one letter: a**, confirm, and verify the remote editor receives exactly `a`. **If it moves the mouse or clicks, disconnect immediately**; do not run the longer test.
8. Once the single-letter probe is correct, tap **5. Send full test text**, confirm, and verify:
   `VaultKey-Test Aa09 !@#-_=+`
9. Compare characters for missing letters or incorrect symbols. Exit the app when done; it unregisters the HID profile and releases resources.

If the second launcher icon is missing, verify the APK is from **standardDebug**, not a production build or an older CI run.

## iQOO 13 (Android 16) — computer stuck at Connecting

Confirmed by the tester: iQOO 13 -> iPhone receives and types test text correctly, but a computer remains at `Connecting` after re-pairing.

This isolates the issue to a **host-specific connection / HID profile negotiation path**, not necessarily the phone's radio and not the ASCII keyboard mapping.

### Diagnostic build changes

- Android HID's `connect(device)` return is only request acceptance, **not connection success**. The test screen now displays the actual HID profile states and an explicit `Check HID connection status` button.
- If the host connected first during Bluetooth pairing, tapping `Connect selected device` **adopts an already-established Bluetooth HID connection** only after the user explicitly selects that bonded host. Incoming hosts are never implicitly authorized to receive text.
- Handle HID `GET_REPORT` (input report ID 0) with an all-keys-released 8-byte reply. Unsupported report writes return an unsupported-request handshake.
- `Check HID connection status` shows `registered=true/false`, real `HID=CONNECTED/CONNECTING/DISCONNECTED`, and the last **non-sensitive HID control request name only**. No keys, account data, or passwords are logged.

### Testing

1. Install the APK from the **latest successful CI commit**.
2. Open VaultKey HID Test on the iQOO 13, start HID and permit Nearby Devices access.
3. Refresh bonded devices, choose the correct computer and tap **Connect selected device** once. The computer may have already connected during pairing.
4. If the phone appears to hang, tap **4. Check HID connection status**. Report the text shown there and the *computer operating system* (Windows 10/11, macOS, or Linux), and specify whether "Connecting" was displayed on the computer or inside the VaultKey app.
5. If it says `HID=CONNECTED`, test single letter `a` inside a blank editor; if the computer is still pairing, wait for system pairing to complete first.
6. If `HID=CONNECTING` or `DISCONNECTED`, do not send text; the computer's host HID profile is not ready. Consider disabling/re-enabling Bluetooth on each side and re-pair **once** if the host holds on to its old descriptor, not repeatedly.
7. If Windows keeps failing while the iPhone works, investigate HIDP host driver/controller behavior and any `GET_REPORT`, `SET_PROTOCOL`, or `SET_REPORT` requests indicated by the diagnostics.

Not yet validated on the user's machine. Do not send production passwords. An iPhone success does not prove that every PC implementation accepts the same HID profile.

## Windows 11: Android reports "Selected host disconnected or connection failed"

### What the error does and does not mean

The error text is generated by the test app when the **Android HID Device profile**
reports `STATE_DISCONNECTED` for the selected host. It is not a Windows error
code and does not tell us which side closed the link.

The same iQOO 13 on Android 16 can type into an iPhone successfully, so key
encoding and the radio are not obviously defective. Windows-side HID profile
negotiation, host Bluetooth drivers, stale SDP data, or the Android vendor
Bluetooth stack remain candidates.

### Diagnostic revision

This revision makes the keyboard descriptor more conventional for Windows:
it declares one **8-byte keyboard input report** and one **1-byte keyboard LED
output report** for NumLock/CapsLock/etc. It accepts valid LED `SET_REPORT`
messages, inspects OUTPUT reports, and responds to `GET_REPORT` with
zeroed (released) keyboard input or LED output. It also records **only HID
control method names, lengths, and profile transitions** — never passwords
or keystroke data.

### Re-test with iQOO 13 and Windows 11

1. Install the artifact produced by the **latest successfully completed**
   `VaultKey Debug CI` build. Do not use an older APK.
2. In Windows 11, go to **Settings → Bluetooth & devices → Devices**,
   remove the previously paired iQOO device. Remove Windows from the iQOO's
   paired Bluetooth devices too. This is important after changing HID
   descriptor/SDP registration.
3. Open the **VaultKey HID Test** Activity on the iQOO and tap
   **Start Bluetooth HID**. Wait for `HID ready` **before**
   Windows starts searching for devices.
4. In Windows choose **Add device → Bluetooth**, select the newly advertised
   device, and finish pairing. Avoid leaving the test Activity during
   pairing: Android can unregister HID applications when they stop being foreground.
5. Back in the HID Test UI, tap **Refresh paired devices**, select the Windows
   PC and **Connect selected device** once.
6. If it fails, tap **Check HID connection status** and record:
   `registered=`, `HID=`, `lastTransition=`,
   and `lastHostRequest=`. Also check Windows **Device Manager →
   Keyboards** and **Human Interface Devices** for a newly appearing
   keyboard/HID device or a device with a yellow warning icon.
7. If `HID=CONNECTED`, test **only one letter `a`** in Notepad.
   If it still fails or moves the pointer, stop; do not use vault passwords.

If this version still reports `DISCONNECTED` and the `lastHostRequest` is
`none`, the link likely ended **before** our Activity saw a HID control
request; we will need Windows driver/device status and Android Bluetooth logs.
If host control requests appeared, their types can help us narrow down the
handshake failure. `SET_REPORT` and `SET_PROTOCOL` themselves are not proof
of success or failure.

No requirement to Root the iQOO 13, install a proprietary Bluetooth driver,
or factory-reset Windows. First troubleshoot the selected device and its
Bluetooth host adapter. Do not uninstall unrelated keyboard devices.

## Mac mini / Hermes test findings — 2026-10-29

Observed on macOS:
- `hidutil list` confirms the phone as Bluetooth **Usage Page 1 / Usage 6** (keyboard).
- With Terminal actually in focus, a single `a` arrived byte-for-byte.
- When Chinese Baidu Pinyin was enabled, the extended typing result was modified by IME composition. Switch input source to **ABC** and ensure Terminal is in front before comparing.
- Bluetooth power-cycle: HID unregisters when Bluetooth is off, then can return to `Ready` when on. The previous implementation's unconditional `start()` made a second `registerApp()` request and could leave the app in Error until force-stop. This is fixed with proxy/registration gating and retryable proxy cleanup.

### Exact expected test strings

The **original** test constant is `VaultKey-Test Aa09 !@#-_=+` — exactly **26 ASCII bytes**, ending in `+`. It **never** contained an `&`. The "27 expected / 26 received" discrepancy is therefore not evidence of a dropped final report.

The debug screen now has two distinct full-string buttons:
- **Baseline (26):** `VaultKey-Test Aa09 !@#-_=+`
- **Extended (27):** `VaultKey-Test Aa09 !@#-_=+&`

The unit test verifies their lengths and the final shifted `&` HID usage. An accepted `sendReport` still does **not** guarantee the host editor received all bytes, so compare the Terminal output twice after switching to ABC.

### Mac hardware regression sequence

1. Install the **latest successful** `VaultKey Debug CI` artifact from this PR. Keep the app in the foreground.
2. With Bluetooth on, tap **START**, confirm `HID ready`, then **START again**. It should remain usable, without registration failed.
3. Select and connect Mac mini; bring a plain Terminal/editor window to the front; switch from Baidu Pinyin to **ABC / English**.
4. Send single letter `a`. Then send baseline (26) and extended (27) *separately*. Verify the bytes and exact final `+` / `&` in the receiving file. Repeat twice.
5. Toggle the phone's Bluetooth **off → on** while keeping the test app foreground. Observe the Ready transition. Tap **START** again, verify no registration failure, then reconnect and test `a`.
6. Repeat the Bluetooth off/on cycle twice. If a failure occurs, the UI should show an actionable error and **START should be retryable** without killing the app. Note the debug diagnostics rather than leaking credentials.
7. `adb logcat -s VaultKeyHID:I` shows only registration/proxy/connection lifecycle events for Debug builds. Never paste full unfiltered `logcat` into public issues; it may contain private information from other apps.

The test screen deliberately keeps Send disabled until a paired host is connected and now explains the prerequisites adjacent to the buttons. The log contains **no typed characters, credentials or MAC addresses**. All this remains debug-only; the vault is not accessed.

### What remains unverified

- Repeated START after Bluetooth off/on **must still be validated on macOS and Android hardware**; the code fix and successful compilation alone cannot prove recovery on every vendor stack.
- Loss of characters under long strings and Windows 11 HID compatibility remain separate tests.

## Redmi K20 Pro / Android 17 field report — critical send interruption (2026-10-29)

Tester: K20 Pro / raphael / SDK 37, Mac mini macOS 26.1, keyboard source ABC.
CI baseline run `40809349392`, commit `7bcbbb9e`.

### Verified working — no need to repeat whole baseline

- 27-byte `VaultKey-Test Aa09 !@#-_=+&` sent and received byte-perfect for 10 consecutive sends.
- Two quick taps on Send resulted in only one transmission.
- Disconnect/reconnect: 9/10 exact (one real link dropout; recoverable).
- No crashes reported; Bluetooth on/off START recovery already fixed.
- Prior missing ampersand was an IME artefact; keep Mac ABC source and capture-focused Terminal.
- One "connection failure" was an accidental selection of Bluetooth headphones rather than a HID host.

### P0: screen-off partial send — mitigated, not atomic

On the old build, locking the phone about half a second into the 27-character
send produced 9/27, 10/27 or 0/27 characters, but the app misleadingly claimed
all reports were sent. This is a blocker for real credentials.

New transport behavior:
- Acquire a **bounded partial CPU wake lock** during an active send (only Debug
  manifest requests `android.permission.WAKE_LOCK`).
- Explicitly abort the current send if the screen turns off or the Activity
  leaves the foreground; each send is tied to an abort generation, so unlocking
  cannot resume the previous partial transmission.
- Require **actual HID CONNECTED**, registered profile, and foreground/unlocked
  screen before first byte and during each key, with a final check.
- Distinguish `NOT SENT`, `INCOMPLETE / UNKNOWN (n of total key presses submitted)`,
  and `All key reports accepted by Android; verify receiving host`.
- Put results in a separate status label, not the connection callback label.
- Never auto-replay partial data. The receiving host must be checked and its
  field **cleared manually** before a retry.

Important: HID is not transactional; a partial password cannot be retracted.
A CPU wake lock is not a screen-on lock and does not guarantee the OS keeps
the HID profile alive. `sendReport()` acknowledgment is from Android,
**not the Mac/PC**. Even full acceptance must be verified on the host before
any real credential integration.

### P1: connecting race and occasional genuine disconnect

- No send is queued while `CONNECTING`. It is refused as `NOT_CONNECTED`.
- If a confirmation dialog opened while connected but the Bluetooth state
  changed before user confirmation, the result clearly shows `NOT SENT`
  rather than silently doing nothing.
- HID transition or registration loss invalidates the in-flight send;
  reconnecting is **a new user action**; pending bytes are not replayed.
- The single intermittent link failure (1/22 in baseline) remains a separate
  stability observation. Don't claim it fixed without a new host trace.

### Focused K20 Pro / Mac regression to run on new build

1. Download the artifact from the **latest successful** `VaultKey Debug CI` run.
   Keep Mac ABC / English active and Terminal capture focused; use a fresh
   capture file for every test.
2. Send the 27-byte test without locking (once). Expect byte-for-byte 27.
3. Send while deliberately pressing the K20 power button after ~0.5s, repeat
   three times. Expect **no false success**. The partial host output may still
   contain a prefix, but the app must report `INCOMPLETE / UNKNOWN` and never
   resume it after unlock.
4. With the Mac receiving field manually cleared, reconnect and send once
   after the interrupted test. Expect full 27 bytes. Do not allow any automatic
   replay when the Activity resumes.
5. After disconnect/reconnect, tap send while profile still reports
   `CONNECTING`. Expect button disabled or an explicit `NOT SENT` result.
6. Repeat manual disconnect and connect while idle; report only unexpected
   registration resets. A disconnect that causes Android to unregister HID
   requires START/REFRESH, but that is a secondary usability issue.

No test needs actual Vaultwarden credentials. Never log credential text,
full keystroke HID reports, or private unrelated logcat entries.

## Boundaries and known limitations

- **Never use real account credentials** for this test; testing does not require logging in to Vaultwarden.
- The test screen always sends fixed, non-sensitive text. No arbitrary textbox to avoid exposing pasted secrets in this stage.
- The transport uses a US keyboard mapping; non-US host layouts can produce wrong symbols.
- This revision uses `SUBCLASS1_KEYBOARD` and a **single 8-byte keyboard report with Report ID 0** (no `0x85` report-ID item in the descriptor). Android `sendReport` takes the report ID as a separate argument.
- **Previous symptom:** the pointer moved or mouse buttons clicked when text was sent. That strongly suggests a host interpreted bytes as mouse reports (possibly an older paired profile). A new APK alone may not update the cached HID descriptor; remove the pairing on both ends.
- If the single-letter probe still moves the pointer after a clean re-pair, note the receiving host OS/version and how Bluetooth paired, and stop testing. The host may be handling the input as a different HID report; further on-device tracing is needed.
- Successful `sendReport` means Android accepted the report; it does not prove the target typed every character.
- Bluetooth HID profiles are system/vendor dependent; pairing on the phone as a keyboard is not always the same as HID Device mode being available to this app.
- This is an experimental test harness. It needs further device security, lifecycle, disconnect, repeated send and UI testing before linking vault items.
- A rooted phone or third-party ROM is acceptable for synthetic test data, not automatically a safe vault terminal.
- USB HID is out of scope.

## Building

The fork requires access to the Bitwarden SDK artifacts in GitHub Packages. If CI reports a 401/403, configure Actions repository secret `BITWARDEN_PACKAGES_TOKEN` with a minimal Classic PAT with `read:packages`. Never commit or paste a token.

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*UsKeyboardReportTest' :app:assembleStandardDebug
```

Workflow: `.github/workflows/vaultkey-debug.yml`. Only the successful **latest commit** build should be installed. Neither this branch nor its workflow publishes to Play Store.

## Next milestone (not implemented)

After receiving verified K20 Pro typing results, review permission/connection lifecycle, keyboard layout handling, and add explicit per-send authentication and safe integration into decrypted login item actions. Keep secret material out of clipboard, logs and saved UI state.
