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
