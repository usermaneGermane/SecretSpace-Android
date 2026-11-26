# SecretSpace for Android

The Android app of [SecretSpace](https://github.com/usermaneGermane/SecretSpace-Client-Windows),
a password manager with its own sync server. **Early version (0.2): make an account
or sign in to one on your server, unlock with your fingerprint, and fill in
logins in other apps.**

It opens the same accounts and vaults as the apps for
[Windows](https://github.com/usermaneGermane/SecretSpace-Client-Windows) and
[Linux](https://github.com/usermaneGermane/SecretSpace-Client-Linux), and syncs
with [SecretSpace Server](https://github.com/usermaneGermane/SecretSpace-Server-Linux)
on your own network. The iPhone app is a separate project.

## Where it is

| Step | State |
|---|---|
| 1. The cryptography, byte for byte as on the PC | done |
| 2. Accounts and vaults on the phone, sign-in from a server | done |
| 3. Sync (the same three-way merge as the PC) | done |
| 4. The app: list, details, edit, generator, 2FA codes (typed or scanned) | done (0.1.0) |
| 5. A new account on the phone, fingerprint / face / PIN unlock, autofill | done (0.2.0) |

## Installing

Download `SecretSpace-Android-<version>.apk` from the
[Releases](https://github.com/usermaneGermane/SecretSpace-Android/releases) page and open it on
the phone (Android 8.0 or newer; the phone asks once to allow installing apps
from the browser or file manager). Then copy the server's certificate file
(`server.crt`, the one the PC uses) to the phone, and either:

- **sign in** to an account you already have, with the server address (as on
  the PC), that certificate file, your username, master password and the
  Secret Key from the Emergency Kit; or
- **create a new account**, with the server address, the certificate file and
  the server's sign-up code (its token: `sudo secretspace-setup show-token` on
  the server). As on the PC, you then save the Emergency Kit (a text file with
  the Secret Key), write down the 12-word recovery phrase and type three of
  its words back. Nothing is written until the server has the account; your
  PCs can then sign in to it.

After that the master password alone opens the account on the phone. The app
locks itself after a minute in the background, blocks screenshots, clears a
copied password from the clipboard after 30 seconds, and keeps nothing in
Android's cloud backup.

## What the app does

- **Unlock with fingerprint** (Settings → Security), as Windows Hello on the
  PC: after a lock your fingerprint, face or, from Android 11, the phone's PIN
  opens the account again. The master password is still needed each time the
  app starts: it is kept only in the app's memory, sealed to a key in the
  phone's secure hardware that works only after the phone has checked you.
  Adding a fingerprint to the phone turns it off again. Off until you turn
  it on.
- **Autofill** (Settings → Security → Autofill): in other apps and browsers
  that use Android's autofill, a login form offers "Fill from SecretSpace".
  Nothing leaves the vault until you tap it, unlock and pick the entry: the
  entries whose web address is that site come first (an app is matched by
  its name), and a search finds any other. New logins are not saved from
  other apps; add them in SecretSpace.
- Every vault of the account, shared ones included (read only where you are a
  reader), with search.
- An entry: copy the username or password, the 2FA code of the moment, the web
  address and notes; earlier versions, which can be put back.
- Add and edit entries, with the password generator and the strength meter; a
  2FA secret typed in or **scanned from the site's QR code** (phone only).
- Archive, restore, delete for good, and the archive's keep-for setting.
- Move an entry to another vault.
- Sync, with the same questions as on the PC when both sides changed an entry.

## How it is kept in step with the PC

`core/` is plain Kotlin: everything that is not Android (formats,
cryptography, sync), tested on a PC without a phone.

- `tools/make_vectors.py` makes test data **with the PC app's own code**;
  `core/src/test/resources/vectors.json` is that data, and the Kotlin tests
  must reproduce every byte of it.
- `tools/make_merge_vectors.py` does the same for the merge, entry history,
  the change log and moves: 169 merges and each way of settling them, with
  the clock and fresh ids replaced by counters on both sides so that every
  byte can be compared.
- `tools/check_on_pc.py` goes the other way: the PC's code opens what the
  Kotlin code made.
- `tools/lab_server.py` starts a real SecretSpace Server over HTTPS and fills
  it **with the PC app's own code**: an account with a personal vault, a
  second vault, and a vault another account shares with it. The Kotlin
  sign-in tests run against it, and afterwards the PC's code opens the account
  folder the phone wrote: the same vaults, nothing left to merge, and a vault
  made on the phone reaches the PC.
- CI does all of this against the PC app's current `main` on every change and
  once a week.

### Syncing (stage 3)

The phone merges exactly as the PC does: three-way, against the copy last
agreed with the server, so a change made on one side is never mistaken for a
deletion on the other; a change on both sides is a question for the user,
and whichever side loses is kept as an earlier version, an archived copy or
a second entry. Each change is signed into the vault's change log. In the
lab test the phone and a PC of the same account take turns, through a real
server: changes, a conflict settled each way, an edit made while the
question was open (refused, nothing lost), an entry added to a vault
another account shares, and at the end the PC finds nothing left to merge.

### Signing in (stage 2)

A phone signs in to an account that already exists on a server, with the
username, master password and the Secret Key from the Emergency Kit. The
Secret Key is then kept on the phone, so afterwards the master password alone
opens the account. The account is stored in the same folder layout as on the
PC. A vault key from the server is taken only when it is signed by the
account itself or by someone it has reason to trust, exactly as on the PC. An
account still in the format of SecretSpace 3 is not converted on the phone:
open it once on a PC first.

## Building

JDK 21, the Android SDK (platform 37.1) and Gradle (the wrapper fetches it):

```bash
./gradlew :core:test                                                    # sign-in tests skipped
py -3 tools/lab_server.py ../SecretSpace-Client-Windows/sourceFiles -- ./gradlew :core:test
./gradlew :app:assembleDebug                                            # the app
py -3 tools/make_vectors.py ../SecretSpace-Client-Windows/sourceFiles   # new test data
```

`env.sh` sets up the build environment used for development, kept apart from
the rest of the PC (JDK, SDK, emulators). Releases are signed with the
project's key, which is not in this repository (`SECRETSPACE_SIGNING`).

## Licence

GPL-3.0, as the rest of SecretSpace.
