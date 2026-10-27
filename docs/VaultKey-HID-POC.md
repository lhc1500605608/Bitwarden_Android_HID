# VaultKey Bluetooth HID proof of concept

## Scope (milestone 1)

This branch adds an **experimental transport layer only**. It does **not** send vault credentials and does **not** expose a Send Password button yet. There is intentionally no UI entry point until permission handling, HID registration, selection/confirmation flow, and reliable report pacing are validated. Do not interpret the presence of the transport class as a working password transmitter.

Added:
- `BluetoothHidKeyboard`: Android Bluetooth HID Device service wrapper.
- `UsKeyboardReport`: printable US ASCII, Tab and Enter keyboard reports.
- Manifest declaration for `BLUETOOTH_CONNECT`, which still requires **runtime permission**.
- Pure mapping unit tests.

## Security boundaries

- Never transmit credentials automatically when opening an item.
- Restrict destinations to explicitly selected, bonded devices; verify the active connection before each operation.
- Obtain confirmation before **every sensitive send**; ideally require device authentication.
- No clipboard, analytics, logs, persistence or background queue for plaintext secrets.
- Fail closed on unsupported characters: UTF-8/Unicode and non-US keyboard layouts are **not** supported.
- HID keyboards cannot verify the remote website or focus; sender must check both.
- The existing official Bitwarden encryption and lock policy must remain authoritative.
- Rooted ROMs are for test data only until platform security is evaluated.

## Remaining work before first hardware test

1. Add debug-only Compose screen/entry (outside the production vault actions).
2. Request Bluetooth runtime permission; check adapter enabled and HID capability.
3. Present bonded devices for explicit selection and show connection state.
4. Queue reports with host-tested press/release pacing and connection cancellation. The current synchronous loop is **not production-safe** and may drop characters.
5. Test US keyboard layout on paired target with harmless text (e.g. `Test-Aa09!@#`).
6. Add explicit per-send consent, clean shutdown, and lifecycle tests.
7. Only then link to the decrypted vault item field behind an explicit action and review.

## Build notes

Upstream Bitwarden Android needs `user.properties` containing a GitHub classic PAT with `read:packages` to fetch Bitwarden SDK artifacts. **Never commit that file or the token.** See root README.

Suggested local checks after SDK access is configured:

```bash
./gradlew :app:testStandardDebugUnitTest --tests '*UsKeyboardReportTest'
./gradlew :app:assembleStandardDebug
```

The repository was edited through GitHub tools; **neither Gradle build nor device testing has been run** in this environment. Verify task names with `./gradlew :app:tasks` if upstream variants differ.

Target hardware: rooted K20 Pro running Android 16, Bluetooth HID already verified independently. USB HID is explicitly out of scope for milestone 1.
