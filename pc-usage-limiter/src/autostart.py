"""Register / unregister the app to run at Windows startup.

Uses the per-user Run key (HKCU) so no admin rights are needed.
On non-Windows platforms these functions are safe no-ops.
"""

import os
import sys

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "PCUsageLimiter"


def _is_windows() -> bool:
    return os.name == "nt"


def _executable_command() -> str:
    """Command Windows should run at startup."""
    if getattr(sys, "frozen", False):
        # Running as a PyInstaller .exe
        return f'"{sys.executable}"'
    # Running from source: relaunch the script with pythonw (no console)
    script = os.path.abspath(sys.argv[0])
    pyw = sys.executable.replace("python.exe", "pythonw.exe")
    return f'"{pyw}" "{script}"'


def enable() -> bool:
    if not _is_windows():
        return False
    import winreg
    try:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ,
                              _executable_command())
        return True
    except OSError:
        return False


def disable() -> bool:
    if not _is_windows():
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, VALUE_NAME)
        return True
    except FileNotFoundError:
        return True  # already gone
    except OSError:
        return False


def is_enabled() -> bool:
    if not _is_windows():
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.QueryValueEx(key, VALUE_NAME)
        return True
    except (FileNotFoundError, OSError):
        return False
