"""Optional, *fully reversible* Windows hardening used only while locked.

- Task Manager can be disabled via the HKCU policy value DisableTaskMgr.
  It is re-enabled the moment the PC is unlocked and on program exit, so you
  never get stuck. (Booting Windows in Safe Mode also bypasses everything,
  because the limiter is just a normal per-user startup program.)

All functions are safe no-ops on non-Windows platforms.
"""

import os

_POLICY_KEY = r"Software\Microsoft\Windows\CurrentVersion\Policies\System"
_VALUE = "DisableTaskMgr"


def _is_windows() -> bool:
    return os.name == "nt"


def disable_task_manager() -> None:
    if not _is_windows():
        return
    import winreg
    try:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, _POLICY_KEY) as key:
            winreg.SetValueEx(key, _VALUE, 0, winreg.REG_DWORD, 1)
    except OSError:
        pass


def enable_task_manager() -> None:
    """Always restore access (delete the policy value)."""
    if not _is_windows():
        return
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, _POLICY_KEY, 0,
                            winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, _VALUE)
    except FileNotFoundError:
        pass
    except OSError:
        # Fall back to explicitly setting 0 (enabled)
        try:
            with winreg.CreateKey(winreg.HKEY_CURRENT_USER, _POLICY_KEY) as key:
                winreg.SetValueEx(key, _VALUE, 0, winreg.REG_DWORD, 0)
        except OSError:
            pass
