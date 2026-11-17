"""CI check: ask every Mojang server about known names and make sure the answers are read right."""
import sys

import checker_core as core

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

sys.exit(0 if ok else 1)
