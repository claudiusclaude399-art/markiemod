"""Detect how long the PC has been idle (no keyboard/mouse input).

Used so that daily usage time only counts while you are actually using the PC.
On Windows this uses GetLastInputInfo. On other platforms it reports 0 (always
active), which is fine for development on Linux/Mac.
"""

import os


def idle_seconds() -> float:
    if os.name != "nt":
        return 0.0
    try:
        import ctypes

        class LASTINPUTINFO(ctypes.Structure):
            _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]

        lii = LASTINPUTINFO()
        lii.cbSize = ctypes.sizeof(lii)
        if not ctypes.windll.user32.GetLastInputInfo(ctypes.byref(lii)):
            return 0.0
        now_ms = ctypes.windll.kernel32.GetTickCount()
        # GetTickCount wraps every ~49.7 days; guard against negative result.
        elapsed = (now_ms - lii.dwTime) & 0xFFFFFFFF
        return elapsed / 1000.0
    except Exception:
        return 0.0
