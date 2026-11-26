"""
A real SecretSpace server with accounts made by the PC client's own code,
for the Kotlin sign-in tests -- and afterwards a check, with the PC's code,
of what the phone wrote.

    py -3 tools/lab_server.py <SecretSpace-Client-Windows>/sourceFiles -- ./gradlew :core:test

1. Starts the server (the PC tests' copy of it) over HTTPS with a
   certificate made here, on a free port.
2. As two PCs: alice, with entries in her personal vault and a vault
   "Team"; bob, who shares his vault "From Bob" with alice as a reader.
3. Writes core/build/lab/lab.json (address, certificate, alice's details,
   what each vault holds) and runs the command after "--" with
   SECRETSPACE_LAB pointing at it.
4. Opens the phone's account folder with the PC's code: the same vaults,
   nothing left to merge, and the vault the phone made reaches alice's PC.
"""

import copy
import datetime
import ipaddress
import json
import os
import shutil
import subprocess
import sys
import threading

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
LAB = os.path.join(ROOT, "core", "build", "lab")
TOKEN = "lab-token-that-is-long-enough"
PW = "lab pass – ünïcode ✓"
CAROL_PW = "carol's pass"
PCS = {}                     # name -> that account, open on its PC
SERVER = {}                  # url, ca_file: for a PC that signs in

split = sys.argv.index("--") if "--" in sys.argv else len(sys.argv)
PC = os.path.abspath(sys.argv[1])
COMMAND = sys.argv[split + 1:]
sys.path.insert(0, PC)
sys.path.insert(0, os.path.join(PC, "tests", "sync_server"))

import account_crypto                                              # noqa: E402
import account_sync                                                # noqa: E402
import accounts                                                    # noqa: E402
import changelog                                                   # noqa: E402
import recovery                                                    # noqa: E402
import secretspace_server                                          # noqa: E402
import vault                                                       # noqa: E402

# What the PC tests do too: accounts cheap to open, the format unchanged.
account_crypto.ARGON2_MEMORY_KIB = 64
account_crypto.ARGON2_PASSES = 1


def certificate(folder, name):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SecretSpace sync server")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(minutes=5))
            .not_valid_after(now + datetime.timedelta(days=2))
            .add_extension(x509.SubjectAlternativeName(
                [x509.IPAddress(ipaddress.ip_address("127.0.0.1")), x509.DNSName("localhost")]), False)
            .add_extension(x509.BasicConstraints(ca=True, path_length=0), True)
            .add_extension(x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), False)
            .sign(key, hashes.SHA256()))
    cert_path = os.path.join(folder, name + ".crt")
    key_path = os.path.join(folder, name + ".key")
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM))
    with open(key_path, "wb") as f:
        f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                  serialization.NoEncryption()))
    return cert_path, key_path


def device(name):
    os.environ["SECRETSPACE_DATA_DIR"] = os.path.join(LAB, name, "data")


def add(account, vault_id, site, password, **extra):
    file = account.vault_file(vault_id)
    before = file.load()
    data = copy.deepcopy(before)
    vault.add_account(data, site, "me", password, **extra)
    changelog.record(data, before, account.username, account.keyset, file.hex)
    file.save(data)


def with_server(account, work):
    session = account_sync.logged_in(account)
    try:
        return work(session)
    finally:
        session.logout()


def sync_vault(account, vault_id):
    session = account_sync.AccountSync(account, None, vault_id)
    try:
        local = session.file.load() if session.file.has_file else {"accounts": []}
        result = session.prepare(local)
        if not session.has_work():
            return local
        assert not result.conflicts, result.conflicts
        return session.commit(local)
    finally:
        session.close()


def sync_all(account):
    changes = with_server(account, lambda s: account_sync.reconcile_vaults(account, s))
    for vault_id in with_server(account, lambda s: account_sync.syncable_vaults(account, s)):
        sync_vault(account, vault_id)
    return changes


def start_server(cert, key):
    secretspace_server.Handler.log_message = lambda *args: None
    httpd = secretspace_server.SyncServer(("127.0.0.1", 0), secretspace_server.Handler)
    httpd.store = secretspace_server.Store(os.path.join(LAB, "store"))
    httpd.token = TOKEN
    httpd.ssl_context = secretspace_server.tls_context(cert, key)
    os.makedirs(httpd.store.root, exist_ok=True)
    threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
    return httpd, f"https://127.0.0.1:{httpd.server_address[1]}"


# ---------------------------------------------------------------------------
# The PCs' side of the sync tests: the Kotlin test asks, the PC's code acts
# ---------------------------------------------------------------------------
def vaults_of(account):
    out = {}
    for vault_id in account.vault_ids():
        file = account.vault_file(vault_id)
        data = file.load()
        checks = [changelog.check(line, file.hex, account.signing_key_for(line["by"]))
                  for line in changelog.lines_of(data)]
        out[file.name] = {"data": data, "log": checks, "role": account.vault_role(vault_id)}
    return out


def pc_action(name, body):
    if name == "carol_edit":
        device("pc-carol")
        carol = PCS["carol"]
        file = carol.vault_file()
        data = file.load()
        before = copy.deepcopy(data)
        for entry in data["accounts"]:
            if entry["site"] == "Mail":
                vault.update_entry(entry, username="mail-from-pc")
            if entry["site"] == "Bank":
                vault.update_entry(entry, password="bank-from-pc")
        changelog.record(data, before, carol.username, carol.keyset, file.hex)
        file.save(data)
        sync_all(carol)
        return {}
    if name == "edit":                 # {who, site, fields}: in the personal vault, then sync
        device("pc-" + body["who"])
        who = PCS[body["who"]]
        file = who.vault_file()
        data = file.load()
        before = copy.deepcopy(data)
        for entry in data["accounts"]:
            if entry["site"] == body["site"]:
                vault.update_entry(entry, **body["fields"])
        changelog.record(data, before, who.username, who.keyset, file.hex)
        file.save(data)
        sync_all(who)
        return {}
    if name == "sync":
        device("pc-" + body["who"])
        sync_all(PCS[body["who"]])
        return {"vaults": vaults_of(PCS[body["who"]])}
    if name in ("carol_sync", "bob_sync"):
        who = name.split("_")[0]
        device("pc-" + who)
        sync_all(PCS[who])                         # sync_vault refuses conflicts
        return {"vaults": vaults_of(PCS[who])}
    if name == "pc_sign_in":           # {who, password, secret_key_text, phrase}: an account made on the phone
        who = body["who"]
        device("pc-" + who)
        account, _ = account_sync.sign_in(SERVER["url"], SERVER["ca_file"], who, body["password"],
                                          body["secret_key_text"])
        sync_all(account)
        PCS[who] = account
        vaults = vaults_of(account)
        device("pc-" + who + "-recovery")              # a PC without the account: the server's copy
        opened = recovery.open_with_phrase(who, body["phrase"], SERVER["url"], SERVER["ca_file"])
        device("pc-" + who)
        return {"vaults": vaults, "fingerprint": account.fingerprint,
                "phrase_opens_it": account_crypto.public_key_of(opened.keyset) ==
                account_crypto.public_key_of(account.keyset)}
    if name == "check_folder":
        os.environ["SECRETSPACE_DATA_DIR"] = os.path.join(body["home"], "data")
        on_phone = accounts.unlock(body["username"], body["password"])
        checks = {}
        for vault_id in on_phone.vault_ids():
            session = account_sync.AccountSync(on_phone, None, vault_id)
            try:
                session.prepare(session.file.load())
                checks[session.file.name] = not session.has_work()
            finally:
                session.close()
        return {"nothing_to_merge": checks}
    raise KeyError(name)


def start_control():
    """POST /<action> with a JSON body: one PC-side step, answered in JSON."""
    import http.server
    lock = threading.Lock()

    class Control(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_POST(self):
            size = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(size) or b"{}")
            try:
                with lock:
                    reply, status = pc_action(self.path.strip("/"), body), 200
            except Exception as e:                      # the test shows it
                import traceback
                reply, status = {"error": f"{type(e).__name__}: {e}",
                                 "trace": traceback.format_exc()}, 500
            raw = json.dumps(reply, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

    control = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Control)
    threading.Thread(target=control.serve_forever, daemon=True).start()
    return f"http://127.0.0.1:{control.server_address[1]}"


def main():
    shutil.rmtree(LAB, ignore_errors=True)
    os.makedirs(LAB)
    cert, key = certificate(LAB, "server")
    other, _ = certificate(LAB, "impostor")
    httpd, url = start_server(cert, key)
    SERVER.update(url=url, ca_file=cert)

    device("pc-alice")
    made = accounts.prepare_account("alice", PW)
    alice = made.save()
    assert account_sync.connect(alice, url, cert, TOKEN) == "created"
    add(alice, None, "Bänk", "p€ss \"q\"", notes="line1\nline2")
    add(alice, None, "Mail", "m41l", url="https://mail.example")
    team = alice.create_vault("Team").hex
    add(alice, team, "Router", "r0uter")
    sync_all(alice)

    device("pc-bob")
    bob = accounts.prepare_account("bob", "bob's password").save()
    assert account_sync.connect(bob, url, cert, TOKEN) == "created"
    sync_all(bob)
    shared = bob.create_vault("From Bob").hex
    add(bob, shared, "Printer", "pr1nt")
    sync_all(bob)
    with_server(bob, lambda s: account_sync.share_vault(
        bob, s, shared, account_sync.lookup_contact(bob, s, "alice"), accounts.ROLE_READER))

    # carol: the sync tests (SyncTest) -- her phone and her PC take turns,
    # and bob shares "Family" with her as an editor.
    device("pc-carol")
    made_carol = accounts.prepare_account("carol", CAROL_PW)
    carol = made_carol.save()
    assert account_sync.connect(carol, url, cert, TOKEN) == "created"
    for site, password in (("Bank", "bank-0"), ("Mail", "mail-0"), ("Shop", "shop-0")):
        add(carol, None, site, password)
    sync_all(carol)
    device("pc-bob")
    family = bob.create_vault("Family").hex
    add(bob, family, "Wi-Fi", "w1f1")
    sync_all(bob)
    with_server(bob, lambda s: account_sync.share_vault(
        bob, s, family, account_sync.lookup_contact(bob, s, "carol"), accounts.ROLE_EDITOR))
    device("pc-carol")
    sync_all(carol)
    PCS.update({"carol": carol, "bob": bob})

    # erin: the app's Sync with its questions (OpenAccountTest)
    device("pc-erin")
    made_erin = accounts.prepare_account("erin", "erin's pass")
    erin = made_erin.save()
    assert account_sync.connect(erin, url, cert, TOKEN) == "created"
    add(erin, None, "Bank", "bank-0")
    sync_all(erin)
    PCS["erin"] = erin

    device("pc-alice")
    sync_all(alice)
    expected = {alice.vault_file(v).name: alice.vault_file(v).load() for v in alice.vault_ids()}
    assert set(expected) == {"Personal", "Team", "From Bob"}, expected.keys()

    phone_home = os.path.join(LAB, "phone")
    lab = {"url": url, "ca_file": cert, "impostor_ca_file": other, "username": "alice",
           "password": PW, "secret_key_text": made.secret_key_text, "phone_home": phone_home,
           "vaults": expected, "roles": {"Team": "owner", "From Bob": "reader"},
           "bob_fingerprint": bob.fingerprint,
           "phone_made": os.path.join(LAB, "phone-made.json"),
           "erin": {"username": "erin", "password": "erin's pass",
                    "secret_key_text": made_erin.secret_key_text},
           "carol": {"username": "carol", "password": CAROL_PW, "secret_key_text": made_carol.secret_key_text},
           "sign_up_code": TOKEN, "control": start_control()}
    lab_file = os.path.join(LAB, "lab.json")
    with open(lab_file, "w", encoding="utf-8") as f:
        json.dump(lab, f, indent=1, ensure_ascii=False)
    print("lab server at", url, flush=True)

    command = list(COMMAND)
    if command and os.path.isfile(os.path.join(ROOT, command[0])):
        command[0] = os.path.join(ROOT, command[0])
    env = dict(os.environ, SECRETSPACE_LAB=lab_file)
    code = subprocess.call(command, cwd=ROOT, env=env) if command else 0
    if code != 0:
        print("the command failed:", code)
        return code
    return check_phone(lab, alice)


def check_phone(lab, alice):
    """The phone's folder, opened by the PC's code."""
    checks = {}
    with open(lab["phone_made"], encoding="utf-8") as f:
        phone_made = json.load(f)

    os.environ["SECRETSPACE_DATA_DIR"] = os.path.join(lab["phone_home"], "data")
    on_phone = accounts.unlock("alice", PW)          # the Secret Key as the phone stored it
    names = {on_phone.vault_file(v).name: v for v in on_phone.vault_ids()}
    checks["the PC opens the phone's account with the password only"] = True
    checks["the same vaults, and the phone's own"] = set(names) == set(lab["vaults"]) | {phone_made["name"]}
    for name, data in lab["vaults"].items():
        checks[f"“{name}” holds the same"] = on_phone.vault_file(names[name]).load() == data
    checks["“From Bob” is a reader's"] = on_phone.vault_role(names["From Bob"]) == "reader"
    bob = on_phone.contact("bob")
    checks["bob's keys remembered, not marked as checked"] = bob is not None and not bob["verified"]
    for name in lab["vaults"]:
        session = account_sync.AccountSync(on_phone, None, names[name])
        try:
            local = session.file.load()
            session.prepare(local)
            checks[f"“{name}”: nothing to merge (the phone's base is right)"] = not session.has_work()
        finally:
            session.close()

    device("pc-alice")
    changes = sync_all(alice)
    got = {alice.vault_file(v).name: alice.vault_file(v).load() for v in alice.vault_ids()}
    checks["alice's PC gets the vault made on the phone"] = phone_made["name"] in changes.added
    checks["with what the phone put in it"] = \
        got.get(phone_made["name"], {}).get("accounts") == phone_made["data"]["accounts"]

    for name, ok in checks.items():
        print(("PASS " if ok else "FAIL ") + name)
    print(f"{sum(checks.values())}/{len(checks)} passed")
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    sys.exit(main())
