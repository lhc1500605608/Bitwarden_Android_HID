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
