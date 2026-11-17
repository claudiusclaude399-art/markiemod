"""CI: compare 'Max' and 'Smooth' pacing against the real Mojang servers (2 minutes each)."""
import re
import time

import checker_core as core

for label, smooth in (("Max", False), ("Smooth", True)):
    pauses = []
    c = core.Checker(core.generate_names(5, 5, core.LETTERS, shuffle=True), threads=12, smooth=smooth,
                     on_status=lambda s: pauses.extend(float(x) for x in re.findall(r"pausing it ([\d.]+)s", s)))
    c.start()
    marks = []
    for _ in range(12):
        time.sleep(10)
        marks.append(c.checked)
    c.stop()
    c.join(10)
    per10 = [b - a for a, b in zip([0] + marks, marks)]
    print(f"[{label}] {c.checked / 120:.1f} names/s over 2 min | names per 10 s: {per10} | "
          f"slow-downs: {len(pauses)}, longest pause {max(pauses, default=0)}s")
    time.sleep(60)          # let Mojang's window reset before the next mode
