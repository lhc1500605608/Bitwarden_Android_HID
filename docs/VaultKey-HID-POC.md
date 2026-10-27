# VaultKey Bluetooth HID prototype (Android / K20 Pro)

## Current milestone: test a harmless Bluetooth HID keyboard

This development branch contains a **debug-only**, separate launcher Activity named **VaultKey HID Test**. It does not read the vault and **cannot send real vault passwords** yet. The purpose is to verify Bluetooth HID hardware compatibility and host typing accuracy.

Implemented:
- Bluetooth HID Device registration through Android public APIs.
- `BLUETOOTH_CONNECT` runtime permission requested by the debug-only test Activity.
- Manual list of already paired/bonded devices and explicit Connect button.
- An additional **Send** confirmation dialog before sending fixed test text.
- US-layout ASCII keycode mapping (including Tab/Enter), with press/release pacing.
- Cancellation handling / best-effort key release, and app cleanup.
- Report encoding unit tests.
- Independent GitHub Actions workflow `VaultKey Debug CI` that builds `standardDebug`.

The debug Activity uses the classic Android UI and is launched from a separate home-screen icon. Production release variants have neither this Activity nor its Bluetooth permission.

## K20 Pro hardware test steps

1. Go to the development PR's latest **VaultKey Debug CI** run. Download the **vaultkey-standard-debug-apk** artifact from the successful run, then unzip the artifact and install the APK on the K20 Pro. **Do not install a failed/partial build.**
2. On the K20 Pro, turn Bluetooth on. In Android Bluetooth settings, pair the target PC or Mac if not already bonded. On the target, open a plain text editor, select a **US** keyboard layout, and focus a blank document.
3. Launch **VaultKey HID Test** (a separate icon from the regular Bitwarden app).
4. Tap **1. Start Bluetooth HID** and approve Android's Nearby devices permission. Wait until `HID ready`.
5. Tap **2. Refresh paired devices**, choose the correct destination in the list.
6. Tap **3. Connect selected device**, then wait for `Connected`. Some host Bluetooth stacks require connecting from the PC side; if it fails, inspect Bluetooth Settings on the target.
7. Tap **4. Send harmless test text** and confirm the dialog. The fixed string is:
   `VaultKey-Test Aa09 !@#-_=+`
8. Compare the receiving text character-by-character. Check for dropped letters, wrong symbols, incorrect uppercase, and connection stability.
9. Exit the test app. It unregisters the HID profile and releases resources.

If the second launcher icon is missing, verify the APK is from **standardDebug**, not a production build or an older CI run.

## Boundaries and known limitations

- **Never use real account credentials** for this test; testing does not require logging in to Vaultwarden.
- The test screen always sends fixed, non-sensitive text. No arbitrary textbox to avoid exposing pasted secrets in this stage.
- The transport uses a US keyboard mapping; non-US host layouts can produce wrong symbols.
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
