"""
Test data for the merge, made by the PC client's own code.

    py -3 tools/make_merge_vectors.py <SecretSpace-Client-Windows>/sourceFiles

writes core/src/test/resources/merge-vectors.json: vaults tidied by
validate_vault, three-way merges and every way of resolving them, entry
history, the change log and moves. The clock, fresh ids and the change
log's random line ids are replaced by counters -- in the Kotlin tests too --
so every value can be compared exactly.
"""

import base64
import copy
import hashlib
import itertools
import json
import os
import sys
import types

sys.path.insert(0, sys.argv[1])
import account_crypto as ac                                    # noqa: E402
import changelog                                               # noqa: E402
import moves                                                   # noqa: E402
import sync                                                    # noqa: E402
import sync_client                                             # noqa: E402
import vault                                                   # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core",
                   "src", "test", "resources", "merge-vectors.json")


class Clock:
    """What the Kotlin tests install as well (MergeTest.fixed)."""

    def reset(self):
        self.ids = itertools.count(1)
        self.stamps = itertools.count(1)
        self.lines = itertools.count(1)

    def new_id(self):
        return "%032x" % next(self.ids)

    def history_now(self):
        return "2026-01-02T03:04:05.%06dZ" % next(self.stamps)

    def urandom(self, n):
        return next(self.lines).to_bytes(n, "big")


clock = Clock()
clock.reset()
UTC_NOW = "2026-01-02T03:04:05Z"
vault.new_id = sync.new_id = clock.new_id
vault.utc_now = sync.utc_now = lambda: UTC_NOW
vault.history_now = clock.history_now
changelog.os = types.SimpleNamespace(urandom=clock.urandom)


def seeded(n):
    return hashlib.sha256(b"secretspace vectors|" + str(n).encode()).digest()


def b64(raw):
    return base64.b64encode(raw).decode("ascii")


KEYSET = {"x25519_private": b64(seeded(100)), "personal_vault_key": b64(seeded(101)),
          "ed25519_seed": b64(seeded(102)), "recovery_proof": b64(seeded(103))}
VAULT_HEX = seeded(60)[:16].hex()
v = {"source": "SecretSpace-Client-Windows sourceFiles (sync, vault, changelog, moves)",
     "utc_now": UTC_NOW, "keyset": KEYSET, "vault_hex": VAULT_HEX}


def run(fn, *args):
    """fn on deep copies, with the counters reset: what the Kotlin side does."""
    clock.reset()
    return fn(*copy.deepcopy(args))


# -- validate_vault -------------------------------------------------------------
def rev(at, **fields):
    return {"site": "S", "username": "u", "password": "p", "replaced_at": at, **fields}


junk = [
    {"accounts": [{"site": "A", "username": "x", "password": "1"},
                  {"site": "A", "username": "x", "password": "2"},      # same content, no id
                  {"id": "dup", "site": "B", "username": "y", "password": "3"},
                  {"id": "dup", "site": "C", "username": "z", "password": "4"},
                  "junk", 7, None,
                  {"site": None, "username": 5, "password": True, "notes": "", "totp": 0,
                   "url": "https://x", "archived_at": "", "moved": {"vault": "v"}},
                  {"id": "m", "site": "M", "archived_at": "2026-01-01T00:00:00Z",
                   "moved": {"vault": "v", "at": "2026-01-01T00:00:00Z"}, "future_field": [1, {"a": "ü"}]},
                  {"id": "h", "site": "S", "username": "u", "password": "p",
                   "history_cleared_at": "2025-06-01T00:00:00.000000Z",
                   "history": [rev("2025-01-01T00:00:00.000000Z", password="old-cleared"),
                               rev("2025-07-01T00:00:00.000000Z", password="p"),        # = current
                               rev("2025-07-02T00:00:00.000000Z", password="a", changed=["password", "zz", "aa", 3]),
                               rev("2025-07-03T00:00:00.000000Z", password="a", by="x" * 70),   # same content: newest kept
                               "junk",
                               rev("2025-07-04T00:00:00.000000Z", password="b", extra_thing="ü✓"),
                               *[rev("2025-08-%02dT00:00:00.000000Z" % d, password="n%d" % d)
                                 for d in range(1, 13)]]}],
     "settings": {"x": 1}, "unknown_top": "kept"},
    {"accounts": []},
]
v["validate"] = [{"in": case, "out": run(vault.validate_vault, case)} for case in junk]
v["validate_errors"] = []
for bad in [[], {"no": 1}, {"accounts": None}, {"accounts": "x"}]:
    try:
        vault.validate_vault(copy.deepcopy(bad))
        raise SystemExit("expected an error")
    except ValueError as e:
        v["validate_errors"].append({"in": bad, "error": str(e)})

# -- the three-way merge ----------------------------------------------------------
BASE_ENTRY = {"id": "e", "site": "Bank", "username": "me", "password": "base-pw",
              "updated_at": "2026-01-01T00:00:00Z",
              "history": [{"site": "Bank", "username": "me", "password": "older",
                           "replaced_at": "2025-12-01T00:00:00.000000Z", "changed": ["password"]}]}
STEADY = {"id": "s", "site": "Steady", "username": "me", "password": "same"}


def op(name, entry, side):
    """One side's change to the entry (None: it is gone)."""
    e = copy.deepcopy(entry)
    clock.reset()
    if name == "none":
        return e
    if name == "purge":
        return None
    if name == "edit_pw":
        vault.update_entry(e, password="new-" + side)
    elif name == "edit_pw_same":
        vault.update_entry(e, password="new-both")
    elif name == "edit_user":
        vault.update_entry(e, username="user-" + side)
    elif name == "archive":
        e["archived_at"] = "2026-01-0%dT00:00:00Z" % (2 if side == "l" else 3)
    elif name == "history_only":
        e["history"] = e.get("history", []) + [{"site": "Bank", "username": "me", "password": "h-" + side,
                                                "replaced_at": "2025-11-0%dT00:00:00.000000Z" % (1 if side == "l" else 2),
                                                "changed": ["password"]}]
    elif name == "extra":
        e["from_the_future"] = {"side": side}
    elif name == "clear_history":
        e["history_cleared_at"] = "2025-12-0%dT00:00:00.000000Z" % (5 if side == "l" else 6)
    return vault.validate_vault({"accounts": [e]})["accounts"][0]


OPS = ["none", "purge", "edit_pw", "edit_pw_same", "edit_user", "archive", "history_only", "extra",
       "clear_history"]


def vault_of(*entries, **extra):
    return {"accounts": [copy.deepcopy(e) for e in entries if e is not None], **extra}


def merge_case(base, local, remote):
    result = run(sync.merge_vaults, base, local, remote)
    out = {"base": base, "local": local, "remote": remote,
           "accounts": result.accounts, "conflicts": [[c.id, c.kind] for c in result.conflicts],
           "stats": result.stats, "extra": result.extra, "summary": result.summary(),
           "needs_push": result.needs_push, "changed": result.changed, "resolved": {}}
    for choice in (sync.KEEP_LOCAL, sync.KEEP_REMOTE, sync.KEEP_BOTH):
        clock.reset()
        again = sync.merge_vaults(copy.deepcopy(base), copy.deepcopy(local), copy.deepcopy(remote))
        choices = {c.id: choice for c in again.conflicts}
        out["resolved"][choice] = sync.merged_vault(local, sync.resolve(again, choices), again.extra)
    return out


v["merges"] = []
base_entry = vault.validate_vault({"accounts": [copy.deepcopy(BASE_ENTRY)]})["accounts"][0]
for with_base in (True, False):
    for lo, ro in itertools.product(OPS, OPS):
        local = vault_of(STEADY, op(lo, base_entry, "l"))
        remote = vault_of(STEADY, op(ro, base_entry, "r"))
        base = vault_of(STEADY, base_entry) if with_base else None
        case = merge_case(base, local, remote)
        case["name"] = f"{'base' if with_base else 'no base'}: here {lo}, there {ro}"
        v["merges"].append(case)

# added on both sides, a first sync, top-level keys of a newer version, "needs a newer app"
new_l = {"id": "nl", "site": "Only here", "username": "a", "password": "b"}
new_r = {"id": "nr", "site": "Only there", "username": "a", "password": "b"}
specials = [
    ("added on each side", vault_of(STEADY), vault_of(STEADY, new_l), vault_of(STEADY, new_r)),
    ("first sync, server empty", None, vault_of(STEADY, new_l), {"accounts": []}),
    ("newer keys", vault_of(STEADY, future={"v": 1}, gone={"x": 1}),
     vault_of(STEADY, future={"v": 1}, mine="l", both={"same": 1}),
     vault_of(STEADY, future={"v": 2}, theirs="r", both={"same": 2}, gone={"x": 1})),
    ("needs a newer app", vault_of(STEADY), vault_of(STEADY, needs_app={"level": 2, "windows": "5.0"}),
     vault_of(STEADY, needs_app={"level": 3, "android": "1.2"})),
    ("needs junk", vault_of(STEADY), vault_of(STEADY, needs_app={"level": True}),
     vault_of(STEADY, needs_app={"level": "2"})),
]
for name, base, local, remote in specials:
    case = merge_case(base, local, remote)
    case["name"] = name
    v["merges"].append(case)

# an entry moved to another vault on the other device: it simply goes
src = vault.validate_vault(vault_of(STEADY, base_entry))
target = {"accounts": []}
clock.reset()
moved_src = copy.deepcopy(src)
before = copy.deepcopy(moved_src)
moves.move(moved_src, target, "e", "ab" * 16)
changelog.record(moved_src, before, "alex", KEYSET, VAULT_HEX)
moved_src["accounts"] = [a for a in moved_src["accounts"] if a["id"] != "e"]   # settled there
case = merge_case(src, copy.deepcopy(src), moved_src)
case["name"] = "moved away there"
v["merges"].append(case)
case = merge_case(src, copy.deepcopy(src), vault_of(STEADY))
case["name"] = "gone there without a move"
v["merges"].append(case)

# -- history ----------------------------------------------------------------------
def history_ops(entry):
    e = copy.deepcopy(entry)
    steps = []
    vault.update_entry(e, password="one", notes="n1")
    steps.append(copy.deepcopy(e))
    vault.update_entry(e, password="one", notes="n1")            # nothing changes
    vault.update_entry(e, url="https://bank.example", totp="JBSWY3DPEHPK3PXP")
    steps.append(copy.deepcopy(e))
    rid = e["history"][-1]["rid"]
    vault.revert_entry(e, rid)
    steps.append(copy.deepcopy(e))
    vault.clear_history(e)
    steps.append(copy.deepcopy(e))
    other = copy.deepcopy(entry)
    vault.update_entry(other, username="someone")
    vault.merge_history(e, other, None, {})
    steps.append(copy.deepcopy(e))
    return {"rid": rid, "steps": steps}


v["history"] = {"in": base_entry, **run(history_ops, base_entry)}

# -- the change log -----------------------------------------------------------------
def log_case(before, after):
    clock.reset()
    data = copy.deepcopy(after)
    count = changelog.record(data, copy.deepcopy(before), "alex", KEYSET, VAULT_HEX)
    clock.reset()
    old_fmt = copy.deepcopy(after)
    changelog.record(old_fmt, copy.deepcopy(before), "alex", KEYSET, VAULT_HEX, ac.OLD)
    return {"before": before, "after": after, "diff": changelog.diff(before, after),
            "count": count, "recorded": data, "recorded_old": old_fmt}


v["changelog"] = []
for lo in OPS:
    after = vault_of(STEADY, op(lo, base_entry, "l"), new_l if lo == "none" else None)
    v["changelog"].append(log_case(vault_of(STEADY, base_entry), after))
many = vault_of(*[{"id": "b%d" % i, "site": "Bulk %d" % i, "username": "", "password": ""} for i in range(25)])
v["changelog"].append(log_case({"accounts": []}, vault.validate_vault(copy.deepcopy(many))))
v["changelog"].append(log_case(vault.validate_vault(copy.deepcopy(many)), {"accounts": []}))
moved_after = vault.validate_vault(vault_of(STEADY, base_entry))
moves.move(moved_after, {"accounts": []}, "e", "cd" * 16)
v["changelog"].append(log_case(vault.validate_vault(vault_of(STEADY, base_entry)), moved_after))
lines = [{"id": "2", "at": "b", "by": "x", "action": "added", "fields": ["password", "Bad", 3], "count": "4",
          "v": 2, "title": "t" * 130, "sig": "s"},
         {"id": "1", "at": "a", "by": "y", "action": "edited", "count": None},
         {"id": "1", "at": "z", "by": "dup", "action": "edited"},
         {"id": "", "at": "a", "by": "y", "action": "edited"},
         {"id": "3", "at": "c", "by": "y", "action": "Not-A-Name"},
         {"id": "4", "at": "d", "by": "y", "action": "added", "count": "x", "v": "2"},
         "junk"]
v["log_merge"] = {"in": lines, "out": changelog.merge(lines, lines[:2])}
signed = v["changelog"][2]["recorded"]["changes"][0]
v["log_check"] = {"line": signed, "signing_key": b64(ac.signing_key_of(KEYSET)),
                  "ok": changelog.check(signed, VAULT_HEX, ac.signing_key_of(KEYSET)),
                  "other_vault": changelog.check(signed, "00" * 16, ac.signing_key_of(KEYSET))}

# -- moves -------------------------------------------------------------------------
clock.reset()
m_src = vault.validate_vault(vault_of(STEADY, base_entry))
m_dst = {"accounts": [{"id": hashlib.sha256(b"move|s|" + b"ef" * 16).hexdigest()[:32]}]}
m_in = copy.deepcopy((m_src, m_dst))
copied = moves.move(m_src, m_dst, "e", "ef" * 16)
copied2 = moves.move(m_src, m_dst, "s", "ef" * 16)       # its id is taken there: a fresh one
settled = copy.deepcopy(m_src)
gone = moves.settle(settled, lambda vault_id, entry_id: entry_id == copied["id"])
v["moves"] = {"in": m_in, "source": m_src, "target": m_dst, "settled": settled, "settled_count": gone,
              "sum": moves._sum(copied)}

# -- which server a base was agreed with ----------------------------------------------
pairs = [("http://10.0.0.3:8787", "https://10.0.0.3:8787/"), ("http://10.0.0.3", "https://10.0.0.3"),
         ("https://Host.Example", "https://host.example:443"), ("https://h:1/a/", "https://h:1/a"),
         ("https://h:1/a", "https://h:1/b"), ("https://[::1]:5", "http://[::1]:5"), ("", "https://h"),
         ("not a url", "not a url"), ("https://h:99999", "https://h")]
v["same_server"] = [{"a": a, "b": b, "same": sync_client.same_server(a, b)} for a, b in pairs]
v["base_matches"] = [{"base": base, "url": url, "revision": r,
                      "matches": sync_client.base_matches(base, url, r)} for base, url, r in [
    ({"accounts": []}, "https://h", 1),
    ({"base_origin": {"url": "https://h", "revision": 5}}, "https://h/", 5),
    ({"base_origin": {"url": "https://h", "revision": 5}}, "https://h", 4),
    ({"base_origin": {"url": "https://h", "revision": "x"}}, "https://h", 4),
    ({"base_origin": {"url": "https://other", "revision": 1}}, "https://h", 4),
    ({"base_origin": {"revision": 9}}, "https://h", 4),
    ({"base_origin": "junk"}, "https://h", 0)]]

os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    json.dump(v, f, indent=1, ensure_ascii=False)
    f.write("\n")
print("wrote", os.path.normpath(OUT), "-", len(v["merges"]), "merges")
