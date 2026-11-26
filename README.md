# SecretSpace for Android

The Android app of [SecretSpace](https://github.com/usermaneGermane/SecretSpace-Client-Windows),
a password manager with its own sync server. **Work in progress: not usable yet.**

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
| 3. Sync (the same three-way merge as the PC) | next |
| 4. The app: list, details, edit, generator, 2FA codes (typed or scanned) | |
| 5. Fingerprint / face / PIN unlock, autofill | |

## How it is kept in step with the PC

`core/` is plain Kotlin: everything that is not Android (formats,
cryptography, sync), tested on a PC without a phone.

- `tools/make_vectors.py` makes test data **with the PC app's own code**;
  `core/src/test/resources/vectors.json` is that data, and the Kotlin tests
  must reproduce every byte of it.
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

JDK 21 and Gradle (the wrapper fetches it):

```bash
./gradlew :core:test                                                    # sign-in tests skipped
py -3 tools/lab_server.py ../SecretSpace-Client-Windows/sourceFiles -- ./gradlew :core:test
py -3 tools/make_vectors.py ../SecretSpace-Client-Windows/sourceFiles   # new test data
```

`env.sh` sets up the build environment used for development, kept apart from
the rest of the PC.

## Licence

GPL-3.0, as the rest of SecretSpace.
