"""
Test vectors from the PC client's own code, for the Kotlin port.

    py -3 tools/make_vectors.py <SecretSpace-Client-Windows>/sourceFiles

writes core/src/test/resources/vectors.json. Every value is made by the
PC client's account_crypto.py, so a test that passes here means the phone
and the PC agree byte for byte. Run it again when the PC's format changes.
"""

import base64
import hashlib
import json
import os
import sys

sys.path.insert(0, sys.argv[1])
import account_crypto as ac                                    # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core",
                   "src", "test", "resources", "vectors.json")
FAST = {"passes": 1, "memory_kib": 64, "lanes": 1}


def b64(raw):
    return base64.b64encode(raw).decode("ascii")


def seeded(n):
    """Fixed bytes, so the file only changes when the format does."""
    return hashlib.sha256(b"secretspace vectors|" + str(n).encode()).digest()


def keyset(n):
    return {"x25519_private": b64(seeded(n)),
            "personal_vault_key": b64(seeded(n + 1)),
            "ed25519_seed": b64(seeded(n + 2)),
            "recovery_proof": b64(seeded(n + 3))}


v = {"source": "SecretSpace-Client-Windows sourceFiles/account_crypto.py"}

# -- Argon2id -----------------------------------------------------------------
v["argon2id"] = [
    {"secret": b64(s), "salt": b64(salt), **cost,
     "out": b64(ac.argon2id(s, salt, cost["passes"], cost["memory_kib"],
                            cost["lanes"]))}
    for s, salt, cost in [
        (b"password", seeded(1)[:16], FAST),
        ("pässwörd-ü".encode(), seeded(2)[:16], {"passes": 2, "memory_kib": 256,
                                                   "lanes": 2}),
        (b"", seeded(3)[:16], {"passes": 3, "memory_kib": 64 * 1024,
                               "lanes": 1}),   # the real cost of a new account
    ]]

# -- Secret Key ---------------------------------------------------------------
v["secret_keys"] = [{"raw": b64(seeded(n)[:16]),
                     "text": ac.format_secret_key(seeded(n)[:16])}
                    for n in range(10, 15)]
text = v["secret_keys"][0]["text"]
old_text = ac.OLD.secret_prefix + "-" + text[3:-1] + \
    ac._secret_check_char(seeded(10)[:16], ac.OLD)
v["secret_key_spellings"] = [
    {"text": text.lower().replace("-", " "), "raw": v["secret_keys"][0]["raw"]},
    {"text": old_text, "raw": v["secret_keys"][0]["raw"]}]
bad = []
for typed in ["", "B4-AAAAA", text[:-1], text[:-2] + ("A" if text[-2] != "A" else "B")
              + text[-1], text.replace(text[5], "0", 1), text[:-1] +
              ("A" if text[-1] != "A" else "B")]:
    try:
        ac.parse_secret_key(typed)
    except ac.CryptoError as e:
        bad.append({"text": typed, "error": str(e)})
v["secret_key_errors"] = bad

# -- account keys -------------------------------------------------------------
v["account_keys"] = []
for n, (password, user) in enumerate([("correct horse", "alex"),
                                      ("ﬁancé Ω", "Mária"),   # NFKC changes both
                                      ("x", "a" * 32)]):
    salt, secret = seeded(20 + n)[:16], seeded(30 + n)[:16]
    for fmt in (ac.NOW, ac.OLD):
        auk, login = ac.derive_account_keys(password, secret, user, salt,
                                            fmt=fmt, **FAST)
        v["account_keys"].append({"password": password, "username": user,
                                  "salt": b64(salt), "secret_key": b64(secret),
                                  "format": fmt.number, **FAST,
                                  "auk": b64(auk), "login_key": b64(login)})

# -- sealed keysets -----------------------------------------------------------
v["keysets"] = []
for n, fmt in enumerate((ac.NOW, ac.OLD)):
    user, password, secret = "alex", "lab-pass-1", seeded(40 + n)[:16]
    salt = seeded(42 + n)[:16]
    ks = keyset(100 + 10 * n)
    if fmt is ac.OLD:
        ks = {"x25519_private": ks["x25519_private"],
              "personal_vault_key": ks["personal_vault_key"]}
    auk, login = ac.derive_account_keys(password, secret, user, salt, fmt=fmt,
                                        **FAST)
    sealed = ac.seal_keyset(auk, ks, salt, ac.KDF_ARGON2ID, FAST["passes"],
                            FAST["memory_kib"], FAST["lanes"])
    if fmt is ac.OLD:       # seal_keyset writes only the current magic
        raw = bytearray(base64.b64decode(sealed))
        header = ac._KEYSET_HEADER.pack(ac.OLD.keyset_magic, ac.OLD.keyset_version,
                                        ac.KDF_ARGON2ID, FAST["memory_kib"],
                                        FAST["passes"], FAST["lanes"], salt)
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        nonce = seeded(44)[:12]
        body = json.dumps(ks, separators=(",", ":"), sort_keys=True).encode()
        sealed = b64(header + nonce + AESGCM(auk).encrypt(nonce, body, header))
    opened, login2 = ac.unlock_account(user, password, secret, sealed)
    assert login2 == login
    v["keysets"].append({
        "username": user, "password": password, "secret_key": b64(secret),
        "sealed": sealed, "format": fmt.number, "login_key": b64(login),
        "keyset": opened,
        "public_key": b64(ac.public_key_of(opened)),
        "signing_key": b64(ac.signing_key_of(opened)),
        "fingerprint": ac.account_fingerprint(ac.public_key_of(opened),
                                              ac.signing_key_of(opened)),
        "signature_of_hello": ac.sign(opened, b"hello"),
        "recovery_proof": b64(ac.recovery_proof(opened))})

# -- recovery phrase ----------------------------------------------------------
v["phrases"] = []
for n in range(3):
    entropy = seeded(50 + n)[:16]
    phrase = ac.entropy_to_phrase(entropy)
    ks = keyset(200 + n)
    v["phrases"].append({"entropy": b64(entropy), "phrase": phrase,
                         "username": "alex",
                         "recovery_key": b64(ac.recovery_key(phrase, "alex")),
                         "recovery_key_old": b64(ac.recovery_key(phrase, "alex",
                                                                 ac.OLD)),
                         "sealed_recovery": ac.seal_recovery(phrase, "alex", ks),
                         "keyset": ks})
words = v["phrases"][0]["phrase"].split()
v["phrase_errors"] = []
for typed in [" ".join(words[:11]), " ".join(words[:11] + ["notaword"]),
              " ".join([words[1], words[0]] + words[2:])]:
    try:
        ac.phrase_to_entropy(typed)
        raise SystemExit("expected an error for " + typed)
    except ac.CryptoError as e:
        v["phrase_errors"].append({"phrase": typed, "error": str(e)})

# -- wrapped vault keys, vaults, names ----------------------------------------
owner = v["keysets"][0]["keyset"]
vault_id, vault_key = seeded(60)[:16], seeded(61)
data = {"accounts": [{"id": "e1", "site": "Bänk", "username": "alex",
                      "password": "p€ss \"q\"", "notes": "line1\nline2"}],
        "settings": {"archive_purge_days": 30}}
v["vaults"] = []
for fmt in (ac.NOW, ac.OLD):
    v["vaults"].append({
        "format": fmt.number, "vault_id": b64(vault_id),
        "vault_key": b64(vault_key), "member": "alex",
        "wrapped": ac.wrap_key(ac.public_key_of(owner), vault_key, vault_id,
                               "alex", fmt),
        "blob": ac.encrypt_vault(vault_key, vault_id, 7, data, fmt),
        "key_generation": 7, "data": data,
        "name": "Wörk ✓",
        "sealed_name": ac.seal_vault_name(vault_key, vault_id, "Wörk ✓", fmt)})

# -- an old keyset that still derives two of its secrets ----------------------
old = {"x25519_private": b64(seeded(70)), "personal_vault_key": b64(seeded(71))}
v["completed_keyset"] = {"in": old, "out": ac.complete_keyset(old)}

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    json.dump(v, f, indent=1, ensure_ascii=False)
    f.write("\n")
print("wrote", os.path.normpath(OUT))
