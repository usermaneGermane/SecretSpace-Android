# SecretSpace for Android

The Android app of [SecretSpace](https://github.com/usermaneGermane/SecretSpace-Client-Windows),
a password manager with its own sync server. **Early version (0.8): make an account
on the phone only or on your server, or sign in to one there; unlock with your
fingerprint, and fill in logins in other apps.**

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
| 6. An account on the phone only, put on a server later if you like | done (0.3.0) |
| 7. The desktop's look: its colours, typeface, cards and tiles, light and dark | done (0.4.0) |
| 8. Vaults: make, rename, delete or leave, put back | done (0.5.0) |
| 9. Sharing a vault: members, roles, the fingerprint check, remove with a new key | done (0.6.0) |
| 10. Activity: the vault's signed change log, and the server's log for owners | done (0.7.0) |
| 11. A new master password, recovery with the 12 words, a new phrase, a delay after wrong passwords | done (0.8.0) |
| 12. Import and export, settings | next, towards 1.0 |

## Installing

Download `SecretSpace-Android-<version>.apk` from the
[Releases](https://github.com/usermaneGermane/SecretSpace-Android/releases) page and open it on
the phone (Android 8.0 or newer; the phone asks once to allow installing apps
from the browser or file manager). Then either:

- **create a new account on this phone only**: a username and master
  password, nothing else. As on the PC, you then save the Emergency Kit (a
  text file with the Secret Key), write down the 12-word recovery phrase and
  type three of its words back. The phone holds the only copy: if it is lost
  or reset, the passwords are gone. Settings → Sync server → **Connect to a
  server** puts the account on your server later, with everything in it;
- **create a new account on your server**: the same, plus the server address
  (as on the PC), the server's certificate file (`server.crt`, the one the PC
  uses, copied to the phone) and the server's sign-up code (its token:
  `sudo secretspace-setup show-token` on the server). Nothing is written until
  the server has the account; your PCs can then sign in to it; or
- **sign in** to an account you already have on your server, with the server
  address, that certificate file, your username, master password and the
  Secret Key from the Emergency Kit.

After that the master password alone opens the account on the phone. The app
locks itself after a minute in the background, blocks screenshots, clears a
copied password from the clipboard after 30 seconds, and keeps nothing in
Android's cloud backup.

## What the app does

It looks like SecretSpace on the PC (its design rules, "direction C"): the
same colours in light and dark, following the phone's setting; the Manrope
typeface (SIL Open Font License, shipped in the app as
`assets/Manrope-OFL.txt`); cards without borders; one green button per
screen for its main action; each site's letter on the same pastel tile as on
the PC; dates written as the PC writes them. A test checks that every text
colour is readable (WCAG AA) on every surface in both modes, and that no
screen uses a colour of its own.


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
- **Vaults** (menu → Vaults), as the PC's Vaults window: each vault with
  where it lives; make one (it goes on the server at the next Sync), rename
  one you own, delete one (on the server first, so it disappears from your
  other devices; for every member if it is shared) or leave one someone
  shared with you, both confirmed with the master password. A vault deleted
  on another device is kept here, marked and not synced, until you delete
  it here too or put it back.
- **Sharing** (Vaults → Share, or Members on a vault shared with you), as
  the PC's Members window: your own fingerprint to read out; **Add a
  person** of the same server as editor, reader or owner, after you compare
  their fingerprint with them (that is what keeps a dishonest server from
  slipping in its own key); change someone's role; **Remove** someone, which
  gives the vault a new key in the same step so they can open nothing
  written afterwards; **Change key**; **Check** a member whose fingerprint
  this phone has not compared yet. The vault key reaches a member only
  wrapped to their own key, signed by yours.
- **Activity** (menu → Activity, or Vaults → Activity), as the PC's Activity
  window: who changed what in a vault, newest first, in the PC's words
  (*dave changed the password of “Mail”*), each line's signature checked
  against its author's key; **Check signatures** fetches the keys of people
  the phone has not met. Owners also see the **Server log**: uploads,
  members added and removed, key changes, with the address each came from.
  Values are never recorded, and neither is who looked at an entry.
- **Master password and recovery phrase** (Settings), as on the PC:
  **Change master password** (on a server, the server is changed first, so
  the two never disagree; other devices sign in again with the new one) and
  **New phrase** (the old words then open nothing). **Forgot your master
  password?** on the unlock and sign-in screens opens the account with its
  12 words: on this phone, or from its server on a phone that does not have
  it. You choose a new master password and get a new phrase and, because
  the phone keeps its Secret Key sealed under the forgotten password, a new
  Emergency Kit. After three wrong master passwords each further try waits
  longer (5 s, 15 s, 1 min, 5 min), as on the PC.
- Every vault of the account, shared ones included (read only where you are a
  reader), with search.
- An entry: copy the username or password, the 2FA code of the moment, the web
  address and notes; earlier versions, which can be put back.
- Add and edit entries, with the password generator and the strength meter; a
  2FA secret typed in or **scanned from the site's QR code** (phone only).
- Archive, restore, delete for good, and the archive's keep-for setting.
- Move an entry to another vault.
- Sync, with the same questions as on the PC when both sides changed an entry.
- **Sync server** (Settings): connect an account that is on this phone only
  to a server (made there with the sign-up code, or, if the account is there
  already, without one), or stop syncing on this phone: the entries stay
  here and the server keeps its copy.

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
