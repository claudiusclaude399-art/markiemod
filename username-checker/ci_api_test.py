"""CI check: ask every Mojang server about known names and make sure the answers are read right."""
import sys

import checker_core as core
import notify

KNOWN_TAKEN = ["Notch", "jeb_", "Dinnerbone"]
SURELY_FREE = ["qzx_9vk2_plw"]     # 12 random chars - nobody has this

ok = True
for ep in core.default_endpoints():
    try:
        taken = core.lookup(ep, KNOWN_TAKEN + SURELY_FREE)
    except Exception as e:
        print(f"[{ep.label}] ERROR: {type(e).__name__} {e}")
        ok = False
        continue
    good = taken == {n.lower() for n in KNOWN_TAKEN}
    ok &= good
    print(f"[{ep.label}] {'OK ' if good else 'BAD'} taken={sorted(taken)}")

one = {n: core.check_one(n) for n in ("Notch", SURELY_FREE[0])}
print(f"[check_one] {one}")
ok &= one == {"Notch": False, SURELY_FREE[0]: True}

# locked-name check: no real login in CI, but a fake token must be rejected as "login wrong/expired"
for label, call in (("token_owner", lambda: core.token_owner("not-a-real-token")),
                    ("check_claimable", lambda: core.check_claimable("Notch", "not-a-real-token"))):
    try:
        print(f"[{label}] UNEXPECTED answer: {call()!r}")
        ok = False
    except core.AuthFailed:
        print(f"[{label}] OK  fake token rejected (AuthFailed)")
    except Exception as e:
        print(f"[{label}] BAD {type(e).__name__}: {e}")
        ok = False

# Discord: a made-up webhook must come back as "doesn't exist", not crash or hang
fake_hook = "https://discord.com/api/webhooks/1/not-a-real-webhook-token"
assert notify.is_webhook_url(fake_hook)
sent, msg = notify.send(fake_hook, notify.build_message(["abc"], {"abc": True}))
print(f"[discord] {'OK ' if not sent and 'exist' in msg else 'BAD'} {msg}")
ok &= (not sent and "exist" in msg)

sys.exit(0 if ok else 1)
