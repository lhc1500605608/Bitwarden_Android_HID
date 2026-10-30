# VaultKey Bluetooth HID prototype (Android / K20 Pro)

## Current milestone — 2026-10-30 23:35 UTC+8: Lock/account races passed

**Hardware accepted on Debug `cdd970f1` / CI `41070092372`:**

- `probe` returned `status=ready, caller_uid=2000` through the
  real ContentProvider Binder caller identity path.
- **T2 PASS — lock before send**: after biometric success with confirmation
  open, real `lock` returned `lock_requested`, receiving Mac captured
  **0 bytes**; real vault locking/transport closure was observed.
- **T3 PASS — lock during send**: real lock issued about 1.5 seconds after
  combined send started; Mac captured 19 of 29 bytes, Android reported
  `INCOMPLETE_OR_UNCERTAIN accepted=19 total=29`. No suffix appeared
  after unlocking.
- **T4 PASS — change active account**: real `switch_other` returned
  `switched`, receiver captured 0 bytes, old transport closed and a
  fresh system verification was required when returning to original entry.
- No crashes. Other previously accepted HID/biometric tests **do not need
  another full regression**.
- **Item deletion and organization policy editing remain UNTESTED**.
- The test-server DNS/sync condition is under the owner's independent
  control; do NOT treat it as a VaultKey bug or debug it here.

### Next code milestone: native master-password reprompt on protected entries

A new DEBUG-only protected-item path is now **implemented but not yet
compiled/verified on hardware**. It reuses the project's
`BitwardenMasterPasswordDialog` and the same
`AuthRepository.validatePassword` used by Bitwarden's credential paths.
The former protected-item HID UI exclusion is removed **only for Debug**.

For items with `CipherRepromptType.PASSWORD` and an existing unlocked
test-vault session, a send attempt must follow:

1. User taps an HID field action; Bitwarden master-password dialog opens.
   The owner enters the master password **personally**. Agent automation
   must NOT capture it, type it via ADB, log it or store it.
2. Wrong password, error or dismissal: **0 host bytes**, no biometric
   approval and no confirmation to the receiver.
3. Successful native password validation: require **fresh platform device
   verification**; the transient reprompt proof is scoped to selected
   item/host/field and current callback epoch, not a reusable boolean.
4. Require 30-second, one-use device-verification grant and final receiver
   name/address confirmation. Read the current field only after confirmation.
5. Revalidate active account, unlocked vault, protected-item status and
   field permission before each key. Abort on lock, app background, profile
   loss or account change. No automatic replay.
6. Next send of the same or another field MUST prompt for master password
   again. No master password or successful verification data is stored in
   SavedStateHandle, Intent, clipboard, disk or logs.

**Focused device test, using the existing synthetic
`reprompt-hid-test` item:**
- After Bitwarden's existing navigation master-password prompt, select
  the HID host. Tap Send username or Send password and observe a
  **new** master-password dialog dedicated to HID.
- Cancel it: 0 bytes. Submit a deliberately incorrect master password
  personally: 0 bytes and explicit failure.
- Submit correct master password personally: system fingerprint dialog
  must follow; cancel fingerprint: 0 bytes.
- Approve the next complete pair of checks, confirm Mac and send a
  synthetic field exactly once. Repeat: master prompt must appear again.
- Revoke via real `lock` during or immediately after master validation,
  or `switch_other`; the old attempt must fail closed.
- If this cannot be demonstrated without inspecting the real master
  password, stop that portion and mark it UNTESTED. Do not bypass the
  master-password requirement for automation.

Release remains disabled. Separate organizational permission audit,
actual item-deletion test with disposable fixture, Release manifest/build
verification and final security review remain open.

---

## Debug-only Binder-protected vault lock/account-switch test control

**2026-10-30: the previous BroadcastReceiver method is retired.** K20 Pro /
KernelSU testing showed `am broadcast` can arrive with AMS
`realCallingUid=2000` but `getSentFromUid()` is unavailable or does not
represent the shell sender. Even `su 2000 -c 'am broadcast ...'` was rejected.
This was correctly categorized as **TEST BLOCKED**, not an app vault-lock
failure. The old receiver and manifest registration have been removed.

The replacement is a **Debug-only exported ContentProvider**,
`VaultKeyDebugSessionProvider`, with
`content://com.x8bit.bitwarden.dev.vaultkeydebug`. It has no data
query/update/insert/delete APIs. Its `call()` method immediately checks
`Binder.getCallingUid() == Process.SHELL_UID (2000)`, Android 14+,
and the APK's `FLAG_DEBUGGABLE`, before fetching Hilt dependencies
through `EntryPointAccessors.fromApplication`. Non-shell callers, including
root UID 0 and normal apps, are rejected; there is no fallback. The
provider and its authority **do not exist in Release APKs**.

On this KernelSU device, **`adb shell` is UID 0**, so invoke the
command **as UID 2000**. Do not use `su` to run the test as root.

### Non-destructive first check: Binder identity probe

```bash
adb shell su 2000 -c 'content call --uri content://com.x8bit.bitwarden.dev.vaultkeydebug --method probe'
```

Expected: Bundle result `status=ready, caller_uid=2000` and logcat
`VaultKeyHID: event=debug_session_probe_ok uid=2000`. It changes no
vault/account state. If it returns `status=rejected`, the log now
includes the actual `uid`, `sdk`, and `debuggable` flags, without
exposing any account identity. **Never relax the identity check to pass.**
On a normal non-rooted phone where `adb shell id -u` is 2000,
`adb shell content call ...` works without the `su 2000` wrapper.

### Real Bitwarden lock without leaving the modal

```bash
adb shell su 2000 -c 'content call --uri content://com.x8bit.bitwarden.dev.vaultkeydebug --method lock'
```

Expected Bundle: `status=lock_requested, caller_uid=2000`.
Logcat: `event=debug_real_lock_requested`. This invokes
`VaultLockManager.lockVault(activeUserId, true)`, not a fabricated
`VaultStateEvent.Locked`. This is **only an invocation acknowledgment**:
inspect real vault locked state and Mac input bytes separately.

### Real account switch to another already logged-in account

```bash
adb shell su 2000 -c 'content call --uri content://com.x8bit.bitwarden.dev.vaultkeydebug --method switch_other'
```

Expected Bundle: `status=switched`; logcat:
`event=debug_real_account_switch result=switched`. The operation calls
`AuthRepository.switchAccount(target.userId)`. The provider selects a
different already logged-in account and accepts **no email/user ID extras**.
If none is available, it returns `status=no_other_logged_in_account`.
With two logged-in test accounts, the next invocation can reverse the
switch; verify the active account before making that assumption.

### Focused T2 / T3 / T4

1. **Probe first.** Require `status=ready caller_uid=2000` before
   beginning any destructive-to-session (but non-destructive-to-data)
   test. Verify the Mac raw HID capture process is alive.
2. **T2: lock after verification, before sending.** Keep the HID host
   confirmation modal visible, issue `--method lock` from the shell.
   Verify real vault lock, 0 bytes, revoked ticket. The modal may close
   automatically; explicitly report that rather than claiming a
   manual Send once click happened.
3. Unlock and reopen the existing synthetic `test1` entry, reconnect
   HID if necessary. **T3: begin 29-byte combination**, issue
   `--method lock` about 1-2 seconds after the first host key, and
   compare accepted presses with captured bytes. Expect only a prefix,
   INCOMPLETE/UNKNOWN, no replay after unlocking. Distinguish lock
   interruption from foreground loss using the command log timestamp.
4. Unlock and restore test1. **T4: authenticate and leave the host
   modal visible**, issue `--method switch_other`. Confirm that the
   active account really changes and the old authorization cannot
   transmit; Mac 0 bytes. Optionally perform a mid-send account switch
   using the same command to verify prefix-only failure.
5. **Item deletion and organization policy edits remain UNTESTED**:
   no destructive debug methods are provided.

The failed broadcast path is intentionally removed, including the
old `VaultKeyDebugSessionReceiver` declaration. **Do not install or
test older APK artifacts.** Check GitHub CI completes successfully,
and use the latest matching commit SHA. Log `event=debug_session_command_rejected
uid=<N> sdk=<N> debuggable=<true|false>` immediately diagnoses a
rejected Binder call. No passwords, TOTP seeds, account IDs or plaintext
HID reports are logged.


---

## 2026-10-30: Live account / vault-lock / item permission fence (under test)

The latest DEBUG-only bridge now obtains a `VaultKeySessionGateViewModel`
via Hilt. It binds the Login item to the **active account ID present when
the item screen is created**, and requires all of the following throughout
every HID transfer:

- The same account remains active, logged in and flagged unlocked by
  Bitwarden's `AuthRepository.userStateFlow`.
- `VaultLockManager.isVaultUnlocked(userId)` still reports unlocked.
- The current `VaultRepository.getVaultItemStateFlow(itemId)` contains the
  same Login cipher; deleted, removed, not-yet-loaded, and
  `CipherRepromptType.PASSWORD` entries fail closed.
- Password or combined action requires the **current** `viewPassword`
  permission; current TOTP action requires personal Premium or
  `organizationUseTotp`, consistent with the existing Login UI.
- Authorization is rechecked immediately before each press and after the
  last press, not only when the system biometric is approved. A revoked
  session produces NOT SENT (0 presses) or INCOMPLETE/UNKNOWN after a
  partial send, with **no automatic replay**.
- Reactive flow listeners also invalidate outstanding confirmation tickets
  and abort transport when account state, vault events or the item changes.

This is a new code change, NOT hardware-verified yet. It is NOT a substitute
for the native master-password reprompt. The Release variant remains closed.

### Focused lock/account regression for the new APK

Use only synthetic `test1` credentials and Mac ABC keyboard capture.

1. Open `test1`, connect Mac and complete one *verified* combined send
   (sanity: 29/29 and actual Tab focus change). Do not repeat old HID matrix.
2. Open system verification; while the prompt is pending, trigger a vault
   lock or account switch using normal Bitwarden controls where possible.
   No valid confirmation or host bytes may follow.
3. After verification succeeds, **lock the vault before Send once**. The
   ticket must become unusable and the host must receive 0 bytes.
4. Start the 29-byte combined send; lock the vault mid-input. The sender
   must stop with INCOMPLETE/UNKNOWN and must never replay a suffix.
   This may require a controlled test-only hook if the real UI cannot
   lock while the Activity remains foreground. Do **not** fake this result.
5. Switch Bitwarden active account while this item screen exists: its
   old authorization must not become valid for the second account.
6. Edit the synthetic test item to disable password visibility or delete
   it before confirming; expect no password HID output.
7. For a master-password-reprompt-protected item, the HID section remains
   absent, as already verified. Real reprompt support is still a blocker.

Only report real device evidence. If an interaction is not practical
without an invasive test hook, mark it **NOT TESTED** and leave release
blocked. Do not share real passwords, private account identifiers,
TOTP seeds or sensitive full logcat in reports.

## Release security gate review — 2026-10-30

**DEBUG transport functionality and on-device biometric tests have passed. This
is NOT yet a release-ready credential transmitter.** Verified acceptance on
CI `41013000270` / commit `375435dd` (K20 Pro Android 17 → Mac mini):
system verification cancellation emitted 0 bytes; approval yielded correct
29-byte username + Tab + synthetic-password stream and true focus transfer;
15-second expiration blocked; cancel/navigation/host switch did not reuse
a grant; screen-off stopped at 24/29 with INCOMPLETE/UNKNOWN and no replay;
synthetic TOTP remained correct. Zero crashes.

### Focused post-acceptance changes (pending new device proof)

- Single-use authorization period extended **from 15 to 30 seconds** to allow
  normal human confirmation. It still starts at **successful** device verification,
  **not** when the system biometric prompt first appears; 30s affects only
  the confirmation, not an in-progress HID transmission.
- A timed effect automatically **closes and revokes** a pending confirmation
  at expiry. The old behavior left a visually valid-looking dialog open until
  clicking Send once returned an expiry error.
- Cancelling a confirmation now invalidates the grant, closes the dialog,
  invalidates prior callback generations and explicitly explains that a
  new system verification is required. The old grant cannot be reused.
- HID actions are **hidden for any item with** `CipherRepromptType.PASSWORD`,
  or where the current cipher/reprompt status is unknown. This is
  **fail-closed**, not master-password reprompt support.

### Evidence from existing Bitwarden architecture

- `VaultItemListingViewModel` already checks
  `CipherRepromptType.PASSWORD` and calls
  `authRepository.validatePassword` in sensitive credential-provider paths.
  Our Login item's HID action is a separate export route and must not
  assume the listing screen's prior reprompt covers it.
- The Login UI provides `canViewPassword` and `canViewTotpCode` and the
  active `currentCipher`. We now deny reprompt-protected items even if their
  decrypted fields are visible.
- `VaultLockManager.isVaultUnlocked(userId)` exists, but the current
  debug HID action has **not** yet bound it to the active account at
  confirmation and during transmission; this requires explicit implementation.
- `BiometricsManager.promptUserVerification` reuses the system
  biometric/device-credential prompt. A successful prompt is **not** proof
  that a Bitwarden master-password reprompt has been satisfied.

### REQUIRED before any Release enablement (unimplemented)

1. Add a **vault session / identity gate**: capture item owner and active
   account identity; at authorization issue, confirmation and every send,
   require current active account matches, vault remains unlocked, and
   item still exists/accessible. Fail closed on logout, account switch,
   lock, item deletion or organization permission change. The transport
   must abort immediately on vault-lock state changes, not just screen-off.
2. Implement the native **master-password reprompt** path for
   `CipherRepromptType.PASSWORD`; call the same repository validation
   used in Bitwarden's protected credential paths. Strong biometric
   alone does not override this requirement. Handle wrong password,
   cancel and lockout without caching credentials.
3. Review item/organization policy constraints beyond UI
   `canViewPassword` and `canViewTotpCode`; recheck permissions against
   the latest vault state at the last responsible moment.
4. Reverify the intended host identity, US keyboard layout and focused
   receiving field for sensitive fields; never infer bytes arrived
   merely from Android accepting HID reports.
5. Run automated negative cases: locked vault, account switch, protected
   item reprompt, organization-managed restriction, host swap, screen
   lock during multi-field input, unsupported characters, repeated sends,
   and release variant without HID action/permissions.
6. Remove test-only routes or explicitly feature-gate them. Review
   release manifests, backup/clipboard/log surfaces and add a
   `standardRelease` compile/build gate before a production launch.

Do **not** remove the current Debug-only build fence or promote the PR.
Keep using only the disposable test vault until the above is implemented
and independently verified.

## CURRENT STATUS — 2026-10-30 (supersedes earlier milestone notes)

**Functionality accepted:** Redmi K20 Pro Android 17 → macOS 26.1 with ABC
input, Debug APK `ba4bfa4c` / CI `40979572160`.
Individual username (5/5), synthetic password (23/23), synthetic TOTP
(6/6; near-expiry rejection) and combined username + Tab + password
(29/29) were captured byte-for-byte on the receiving host. Combined Tab
navigated a real two-field offline HTML login form. Mid-combination
screen-off stopped at 25/29, showed INCOMPLETE/UNKNOWN and did not replay.
The selected Mac's real name was shown instead of Unknown; no crashes.

**Latest change: re-enable required device user verification for every field**
(including the combined login send). The previously built Debug functional
bypass is **no longer active** on this branch. Reuse Bitwarden's
`BiometricsManager.promptUserVerification` for BIOMETRIC_STRONG or
DEVICE_CREDENTIAL; no custom PIN, no shortcut. A success produces a
15-second, single-use, in-memory `HidOneShotVerification` ticket bound to
`vaultItemId + selected Bluetooth MAC + exact field`. The combined
USERNAME+TAB+PASSWORD field has its **own grant type** and cannot reuse a
USERNAME or PASSWORD grant. The second confirmation shows **host name plus
Bluetooth MAC**; on cancellation, host switch, profile loss, app pause,
navigation, or timeout, no unconsumed grant may be used. Password and TOTP
still use existing Bitwarden field-view permissions; TOTP is read after
confirmation, rejecting codes with less than 5 seconds remaining.

### Focused device-auth regression

Install the **newest successful** `VaultKey Debug CI` APK after this
authentication change. Use only the disposable `test1` Login item and
synthetic credential/TOTP values. Close the standalone HID Test Activity.

1. Connect the Mac and select `Send username + Tab + password`.
   Verify a real OS biometric/device-credential prompt appears **before**
   the host confirmation. Cancel it: expect 0 Mac bytes.
2. Approve a fresh prompt. The confirmation must include the intended
   Mac's name **and** its Bluetooth MAC. Confirm within 15 seconds:
   expect 29 bytes (5 username + Tab + 23 fake password), a real Tab focus
   transition, and an Android `REPORTS_ACCEPTED` result.
3. Repeat biometric verification, then wait 16+ seconds at the host
   confirmation: expect 0 bytes and explicit expiry/no grant.
4. Verify successfully then **cancel the host confirmation**. No sends
   should occur, and the next attempt must prompt for device verification
   again. Switch selected host or navigate away during a pending
   verification: no send or stale authorization.
5. Verify then send TOTP after confirming the host, comparing the 6-digit
   code in the same time window. A current TOTP with <5 seconds left
   must still be rejected, even following user verification.
6. Optional: after successful device verification, interrupt a long
   synthetic combined send by locking the screen. The sender must still
   report INCOMPLETE/UNKNOWN and must not auto-replay any suffix.

The authentication code is **debug-only**, still **NOT production ready**.
A system device credential prompt does not fulfill a Bitwarden policy's
master-password reprompt. Active-account/vault-lock policy handling,
organization restrictions, audit, and production release integration remain
separate mandatory security work before using any real password.
No screenshots or logs of credentials or TOTP seeds.

---
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

## 2026-10-29: P0 acceptance and first real vault screen integration

### Latest hardware acceptance (CI 40859778044, commit a844db1d)

- Redmi K20 Pro (Android 17), Mac mini (macOS 26.1), keyboard layout ABC.
- Screen-off mid-send ×3: host captured 9/27 each time; Android reported
  `INCOMPLETE / UNKNOWN: 9 of 27 key presses accepted...`; no automatic resend.
- Explicit reconnect after interruption ×3: 27/27 bytes each time.
- Connecting-race test: two observed UI `CONNECTING` windows successfully sent
  27/27, with no silent drops; actual Bluetooth HID state may have become
  CONNECTED between UI rendering and the user confirmation/send call.
- No crash in this test. One prior rare HID link dropout remains under observation.

**Gate decision:** never queue a send while connecting. A send is accepted only
when the actual Android HID Device profile reports `STATE_CONNECTED` on
the selected device at the beginning of `sendAscii()`, and is checked again
between keypresses. A stale UI `CONNECTING` text is not authoritative.

### First login item integration checkpoint

A DEBUG-only `VaultKey HID · Debug username integration` action now appears
on a Bitwarden **Login** item's content screen when the item has a username.

This uses the existing decrypted login UI data: no second vault, no clipboard,
no Intent extra, no persistent plaintext state. The user must:

1. Use a **throwaway test vault entry with a synthetic username**; do not use
   real credentials or connect the test to a production Vaultwarden account yet.
2. Install the newest successful `standardDebug` APK, log into that test vault,
   open a Login item.
3. In `VaultKey HID · Debug username integration`, tap **HID host**;
   grant Nearby Devices permission if needed. START/registration is automatic,
   but wait for the transport to become READY.
4. Open **HID host** again if needed. Refresh the list, select the receiving
   Mac/PC explicitly, and press **Connect selected host**.
5. Focus an ABC/English text field on the receiving Mac/PC.
6. Tap **Send username** and verify the **host name** in the confirmation
   dialog. Confirm once. Compare target bytes with the known test username.
7. Repeat with phone screen-off during input. The transport must stop and
   show INCOMPLETE/UNKNOWN, with no replay. Clean the host field before
   manually retrying.

Password and TOTP HID actions are intentionally **not yet available**.
Before enabling them: integrate on-device reauthentication at send time,
recheck vault unlock and permissions, validate supported keyboard layout,
keep the device/field confirmation, and test lock/cancel/race behavior with
synthetic credentials. Never cache a real password in an ActivityResult,
Intent, SavedStateHandle, clipboard or telemetry.

The debug test launcher also registers the same system-wide HID profile.
**Close the standalone HID test Activity before using this vault integration**
to avoid a second `registerApp` competing with it. Debug-only bridge
lifecycle automatically closes its HID proxy when the composable is disposed.

## 2026-10-29: Vault username HID integration accepted; authentication gate prototype

### Evidence from CI 40868327764 / commit 0796704a

Mac mini with ABC keyboard input, `test1` Login entry, synthetic username `admin`:
- **T1 PASS** — host byte capture `b'admin'` = expected 5 bytes, app reported all 5 reports accepted by Android.
- **T2 PASS** — phone Bluetooth disabled, no confirmation or host bytes; after Bluetooth re-enabled, still no queued replay.
- **T3 PASS** — pressing power immediately mid-send resulted in `b'admi'` (4/5), app reported `INCOMPLETE / UNKNOWN: 4 of 5`, host captured no replay after unlock.
- Crash buffer 0. Earlier HID 27-byte and 26-byte synthetic tests already passed.
- Because `admin` is only 5 bytes, optionally change the synthetic test username to
  `testuser-synthetic-20261029xyz` to create a reliable interruption window.
  This longer test is a **supplementary regression**, not a blocker for the auth prototype.

### Security review: PASSWORD / TOTP must be gated separately

The existing Bitwarden project provides `BiometricsManager.promptUserVerification()`
using strong biometrics or device credentials, already used in the repository's
FIDO2 user-verification path. Reuse it — do not build custom PIN or biometric UI.

**Design, not yet enabling real secrets:**
1. Open an already-unlocked Login item inside the normal Bitwarden UI. Never
   export a decrypted item through an Intent, clipboard, or debug log.
2. Manually select a bonded HID receiving host and confirm its identity.
3. Choose the **field** to transmit; require a **fresh successful device user
   verification on every send**, not merely an unlocked app.
4. Issue an **in-memory, single-use, 15-second** verification grant scoped to
   exact `vaultItemId + receivingHostAddress + fieldType` (USERNAME, PASSWORD,
   or TOTP). No secret text or token is persisted. Reject expired, cancelled,
   cross-host, cross-item, cross-field, or reused grants.
5. After verification, present a second explicit **receiving host + focused
   input field** confirmation. Consume the grant before invoking HID transport.
   Transport still enforces foreground/screen, current HID connection and
   no-replay rules.
6. On vault lock/logout/account switch, item navigation, Activity pause,
   host change, cancellation, or expiration, drop the authorization.
7. When **password** is eventually enabled: only if the active item permits
   viewing/copying its password (`passwordData.canViewPassword`); respect
   organization/collection policies and vault reauthentication requirements.
   Retrieve the password at the last possible moment from current unlocked
   state; don't snapshot/store it in the authorization grant.
8. When **TOTP** is eventually enabled: require `canViewTotpCode`, use a
   **fresh code after confirmation** rather than holding a code across biometric
   prompts. Reject a code near expiry if reliable validity information is
   unavailable. Never send an old TOTP after reconnection.
9. A biometric/device-credential verification is **not equivalent to Bitwarden
   master-password reprompt** if a policy requires the master password. Those
   policies must be separately honored before enabling PASSWORD/TOTP.

**First implementation checkpoint:** DEBUG Login screen **username** action
now runs the existing Bitwarden `promptUserVerification` before the explicit
host confirmation. The authorization object `HidOneShotVerification` is
tested for one-use, expiration, item/host/field isolation and invalidation.
No PASSWORD or TOTP send button exists yet.

### Focused auth proof to test

1. Install the latest successful Debug CI APK. Use a throwaway vault Login
   item with a synthetic username (optionally 30–40 ASCII characters).
2. Connect the Mac HID host. Tap **Verify + send username**. Confirm Android's
   system biometric/device-credential prompt is shown.
3. **Cancel verification**: no send, 0 Mac bytes.
4. **Approve verification**, check the host in the second dialog and confirm
   within 15 seconds: username bytes must match exactly, once.
5. **Wait over 15 seconds** after verifying, then confirm: 0 bytes, explicit
   expiry message.
6. **Cancel the second dialog**, then repeat: an old verification must not be
   reusable; a new biometric prompt is required.
7. **Disconnect, lock, background, or navigate to another Login item** during
   verification: never use the old grant or auto-send.
8. If strong biometrics/device credential verification is unavailable, the
   feature must fail closed (not silently skip reauthentication).

**Release criterion:** successful unit tests, app build and fresh hardware
biometric proof using only synthetic data. No real password or TOTP until
the above controls and organization policy checks are reviewed and proven.

## Current mode: DEBUG functional testing without biometric prompts

**Temporary owner-requested decision (2026-10-29):** automated Android testing
cannot use the phone fingerprint sensor or enter the owner's lock-screen PIN.
Biometric/device-credential verification has already been tested successfully;
temporarily make it **non-blocking in DEBUG builds** to finish HID field
functionality. The `HidOneShotVerification` helper and its unit tests are
retained for the security-hardening phase, but are not used by these debug
functional send buttons.

- The unlocked Bitwarden Login detail shows one **DEBUG FUNCTION TEST** section.
- It offers `Send username`, `Send password`, `Send current TOTP` where
  the field exists. Password is only offered if Bitwarden sets
  `passwordData.canViewPassword`; TOTP is only offered if
  `canViewTotpCode`. This is a transport test, **not permission bypass**.
- Tapping send requires an active selected HID host, then an explicit target
  confirmation, which an ADB test agent can click. There is **no device unlock
  prompt** on this temporary flow. Never use real account credentials on this
  build.
- TOTP is read from the *latest* Compose state after the confirmation. It is
  not allowed if fewer than 5 seconds remain or the value is not 6–8 digits.
  The code is never logged or shown in the confirmation dialog. This is a
  preliminary rollover check, **not** a final time-safe TOTP design.
- Each send still uses the transport's no-queue, foreground, display-on,
  actual-HID-CONNECTED and no-replay-on-interruption behavior.
- Production/release builds never expose the actions because the UI call site
  and the component both check `BuildConfig.DEBUG`.
- Do not merge this test mode to production. Restore per-send reauthentication,
  master-password reprompt as required, and an audited authorization boundary
  before enabling real credentials.

### Focused follow-up test using synthetic credentials

Use a disposable test vault Login item only; set synthetic values such as
username `testuser-synthetic-20261029xyz` and a unique **test-only**
ASCII password (never reuse any real password). Configure a fake TOTP test
secret if you want to validate rotating digits; never use an account's
real TOTP seed.

1. Install the latest successful `VaultKey Debug CI` APK and open the
   test Login entry. Close the standalone HID test Activity first.
2. Connect the Mac HID receiver using `HID host`.
3. **Username:** send once; compare host capture exactly to the synthetic
   username bytes.
4. **Password:** send once; compare host capture exactly to the synthetic
   password bytes, without screenshots/logs containing its content if you
   later move to real credentials.
5. **TOTP:** record the *test* code and time left in the app, confirm and
   send while at least 5 seconds remain; compare exact 6–8 digits, and
   test near-expiry to ensure fail-closed. No automatic resend across rollover.
6. With each send button, verify the dialog identifies the selected host.
   Disconnect Bluetooth before confirmation to ensure no queued text.
7. Do one longer synthetic password send while immediately turning off the
   screen; verify INCOMPLETE/UNKNOWN and no automatic replay. Manually clear
   the receiving text field before a new attempt.

Pending work: username + TAB + password sequential input, explicit target
switching/reconnect tests, standard keyboard layout handling, password/TOTP
security gate re-enablement, code near-expiry handling and release policy.

## 2026-10-30: Field-by-field validation complete; combined login introduced

### Verified on Redmi K20 Pro -> Mac mini, Debug build afdb454a / CI 40900282041

- USERNAME: `admin` (5 bytes) received exactly. Android reported 5/5.
- PASSWORD: 23-character **synthetic test password** received exactly; mixed
  case and US punctuation such as @/# verified. Android reported 23/23.
  Keep the password out of logs, screenshots and test reports.
- TOTP: A disposable 32-character Base32 TOTP secret was installed manually
  into the test Login item. Mac received the correct six-digit HMAC-SHA1
  code for the **same 30-second time window**. Android reported 6/6.
- TOTP with three seconds remaining: **0 host bytes**, explicit `NOT SENT`
  because less than five seconds remained. Confirmed a fresh code is chosen
  after the confirmation dialog, not cached when it opens.
- No crashes. Ignore unrelated autofill provider and graphics-stack warnings.
- The host's capture process exiting previously caused an apparent 0-byte
  send despite Android acceptance. Verify the host capture process is alive.

### Next: combined login functional test (new patch)

The DEBUG-only Login action now offers **Send username + Tab + password**
when both fields are available and password-view permission permits it.

- Uses **one** `sendAscii(username + "\\t" + password)` call — one
  send mutex, CPU wake-lock window, one interruption outcome, no automatic
  retry or replay between fields.
- Tab is the standard HID keyboard usage `0x2B`, not a fake character and
  not an explicit file byte until interpreted by the receiving host.
- Reject embedded line breaks or Tab inside either test field before
  constructing a combined command, and reject unsupported characters
  atomically before sending any keystrokes.
- The target-host confirmation derives a live Bluetooth name from the selected
  device; if unavailable, shows its Bluetooth address instead of `Unknown`.
- Debug mode still has **NO BIOMETRIC GATE** by owner's request to support
  ADB automation. Never use real credentials or merge this mode as release.

**Focused test on a disposable Login entry `test1`:**

1. Install the APK from the **latest successfully completed** VaultKey Debug
   CI run. Keep the receiving Mac on the **ABC** keyboard layout.
2. Confirm the dialog says the intended Mac mini (or its address), not
   `Receiving host: Unknown` or a stale headphones name.
3. Use the button **Send username + Tab + password**. For raw byte capture,
   configure the terminal in raw/no-echo mode so `Tab` is not intercepted
   by the line editor; expected capture is
   `username.encode(US_ASCII) + b'\\x09' + password.encode(US_ASCII)`.
4. Verify practical **focus transfer** separately: put two input fields
   side by side (e.g. a local offline dummy login form), focus username,
   send combined, and confirm the password goes to the **password field**,
   not the username field. Record no password text, only match yes/no.
5. Confirm no second send occurred, no missing first password character,
   no duplicate Tab. Also test screen-off mid-combination once:
   report INCOMPLETE/UNKNOWN, no automatic completion after unlock.
6. Don't repeat earlier validated stand-alone username/password/TOTP tests
   unless the combined change reveals a regression.

Still pending: permissions/authentication/re-prompt hardening and eventual
release integration. The independent user verification grant/tests remain
available, but are deliberately not active in this debug functionality build.

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
