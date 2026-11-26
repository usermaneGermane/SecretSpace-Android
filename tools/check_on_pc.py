"""
Open what the Kotlin port made, with the PC client's own code.

    ./gradlew :core:test
    py -3 tools/check_on_pc.py <SecretSpace-Client-Windows>/sourceFiles
"""
import base64
import json
import os
import sys

sys.path.insert(0, sys.argv[1])
import account_crypto as ac                                    # noqa: E402

made = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                   "core", "build", "made-on-android.json"), encoding="utf-8"))
b = lambda k: base64.b64decode(made[k])                        # noqa: E731
secret = ac.parse_secret_key(made["secret_key_text"])
keyset, login = ac.unlock_account(made["username"], made["password"], secret, made["sealed_keyset"])
checks = {
    "keyset opens with the password": keyset == made["keyset"],
    "same login key": login == b("login_key"),
    "current format": ac.keyset_format(made["sealed_keyset"]) is ac.NOW,
    "wrapped key opens": ac.unwrap_key(keyset, made["wrapped"], b("vault_id"), made["username"]) == b("vault_key"),
    "vault opens": ac.decrypt_vault(b("vault_key"), made["blob"], b("vault_id")) == made["data"],
    "vault header": ac.vault_header(made["blob"])["key_generation"] == 4,
    "name opens": ac.open_vault_name(b("vault_key"), b("vault_id"), made["sealed_name"]) == "Wörk ✓",
    "recovery copy opens": ac.recover_account(made["username"], made["phrase"], made["sealed_recovery"]) == keyset,
    "signature checks": ac.verify(ac.signing_key_of(keyset), b"hello", made["signature"]),
}
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
print(f"{sum(checks.values())}/{len(checks)} passed")
sys.exit(0 if all(checks.values()) else 1)
