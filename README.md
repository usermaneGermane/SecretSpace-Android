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
| 2. Accounts and vaults on the phone, sign-in from a server | next |
| 3. Sync (the same three-way merge as the PC) | |
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
- CI does both against the PC app's current `main` on every change and once a
  week.

## Building

JDK 21 and Gradle (the wrapper fetches it):

```bash
./gradlew :core:test
py -3 tools/make_vectors.py ../SecretSpace-Client-Windows/sourceFiles   # new test data
```

`env.sh` sets up the build environment used for development, kept apart from
the rest of the PC.

## Licence

GPL-3.0, as the rest of SecretSpace.
