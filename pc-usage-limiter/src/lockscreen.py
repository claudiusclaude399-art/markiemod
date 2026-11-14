"""Full-screen lock overlay shown when the PC is locked.

Design goals:
- Cover the whole screen, stay on top, and keep grabbing focus.
- Block the obvious escape routes (Alt+F4, the window close button).
- ALWAYS remain escapable via the recovery code -> you can never brick the PC.
- Optionally block global hotkeys (Alt+Tab / Win) if the `keyboard` package
  is installed and the app runs with admin rights. Degrades gracefully if not.
"""

import time
import tkinter as tk

try:
    import keyboard as _kbd  # optional, Windows + admin
except Exception:  # pragma: no cover - optional dependency
    _kbd = None

# Hotkeys we try to suppress globally while locked (best effort).
_BLOCKED_HOTKEYS = [
    "alt+tab", "alt+esc", "ctrl+esc", "left windows", "right windows",
    "alt+f4", "ctrl+shift+esc",
]


class LockScreen:
    def __init__(self, tr, config, verify_fn, on_unlock, reason="manual",
                 info_provider=None):
        """
        tr            : Translator
        config        : dict (for max_attempts / cooldown)
        verify_fn     : fn(code) -> "unlock" | "master" | None
        on_unlock     : fn(kind) called when a valid code is entered
        reason        : "limit" | "schedule" | "manual"
        info_provider : optional fn() -> dict with live status strings
        """
        self.tr = tr
        self.config = config
        self.verify_fn = verify_fn
        self.on_unlock = on_unlock
        self.reason = reason
        self.info_provider = info_provider

        self.attempts = 0
        self.max_attempts = int(config.get("max_attempts", 5))
        self.cooldown_seconds = int(config.get("cooldown_seconds", 30))
        self.cooldown_until = 0.0

        self.win = None
        self._hooked = False

    # ---- global hotkey blocking (optional) -------------------------------
    def _hook_keys(self):
        if _kbd is None:
            return
        try:
            for hk in _BLOCKED_HOTKEYS:
                _kbd.block_key(hk) if " " in hk and "+" not in hk else \
                    _kbd.add_hotkey(hk, lambda: None, suppress=True)
            self._hooked = True
        except Exception:
            self._hooked = False

    def _unhook_keys(self):
        if _kbd is None or not self._hooked:
            return
        try:
            _kbd.unhook_all_hotkeys()
            _kbd.unhook_all()
        except Exception:
            pass
        self._hooked = False

    # ---- lifecycle -------------------------------------------------------
    def show(self, root):
        if self.win is not None:
            return
        tr = self.tr
        self.win = tk.Toplevel(root)
        w = self.win
        w.configure(bg="#0b1020")
        w.attributes("-fullscreen", True)
        w.attributes("-topmost", True)
        try:
            w.overrideredirect(True)   # remove title bar / close button
        except tk.TclError:
            pass
        # Swallow the close button / Alt+F4
        w.protocol("WM_DELETE_WINDOW", lambda: None)
        w.bind("<Alt-F4>", lambda e: "break")

        container = tk.Frame(w, bg="#0b1020")
        container.place(relx=0.5, rely=0.5, anchor="center")

        tk.Label(container, text="🔒", font=("Segoe UI Emoji", 64),
                 bg="#0b1020", fg="#ffffff").pack(pady=(0, 10))
        tk.Label(container, text=tr.t("locked_title"),
                 font=("Segoe UI", 34, "bold"),
                 bg="#0b1020", fg="#ffffff").pack()

        reason_key = {
            "limit": "locked_limit_msg",
            "schedule": "locked_schedule_msg",
        }.get(self.reason, "locked_manual_msg")
        tk.Label(container, text=tr.t(reason_key),
                 font=("Segoe UI", 16),
                 bg="#0b1020", fg="#9fb3ff").pack(pady=(6, 20))

        self.info_label = tk.Label(container, text="", justify="center",
                                   font=("Segoe UI", 13),
                                   bg="#0b1020", fg="#c7d2fe")
        self.info_label.pack(pady=(0, 16))

        tk.Label(container, text=tr.t("enter_code"),
                 font=("Segoe UI", 14),
                 bg="#0b1020", fg="#ffffff").pack()

        self.entry = tk.Entry(container, show="•", justify="center",
                              font=("Consolas", 20), width=22,
                              bg="#1e293b", fg="#ffffff",
                              insertbackground="#ffffff", relief="flat")
        self.entry.pack(pady=10, ipady=6)
        self.entry.bind("<Return>", lambda e: self._try_unlock())

        self.unlock_btn = tk.Button(container, text=tr.t("unlock_button"),
                                    font=("Segoe UI", 14, "bold"),
                                    bg="#2563eb", fg="#ffffff",
                                    activebackground="#1d4ed8",
                                    activeforeground="#ffffff",
                                    relief="flat", padx=24, pady=8,
                                    command=self._try_unlock)
        self.unlock_btn.pack(pady=(4, 10))

        self.msg_label = tk.Label(container, text="",
                                  font=("Segoe UI", 12),
                                  bg="#0b1020", fg="#f87171")
        self.msg_label.pack()

        self._hook_keys()
        self._keep_on_top()
        self._refresh_info()
        # Grab everything and focus the entry.
        w.after(100, self._grab)

    def _grab(self):
        if self.win is None:
            return
        try:
            self.win.grab_set_global()
        except tk.TclError:
            try:
                self.win.grab_set()
            except tk.TclError:
                pass
        self.entry.focus_force()

    def _keep_on_top(self):
        if self.win is None:
            return
        try:
            self.win.lift()
            self.win.attributes("-topmost", True)
            self.win.focus_force()
        except tk.TclError:
            return
        self.win.after(500, self._keep_on_top)

    def _refresh_info(self):
        if self.win is None:
            return
        if self.info_provider:
            info = self.info_provider() or {}
            lines = []
            if "time_used" in info:
                lines.append(f"{self.tr.t('time_used_today')} {info['time_used']}")
            if "current_time" in info:
                lines.append(f"{self.tr.t('current_time')} {info['current_time']}")
            self.info_label.config(text="\n".join(lines))
        self.win.after(1000, self._refresh_info)

    def _try_unlock(self):
        now = time.time()
        if now < self.cooldown_until:
            remaining = int(self.cooldown_until - now)
            self.msg_label.config(text=self.tr.t("cooldown_msg", s=remaining))
            return
        code = self.entry.get().strip()
        self.entry.delete(0, tk.END)
        kind = self.verify_fn(code)
        if kind:
            self._close()
            self.on_unlock(kind)
            return
        self.attempts += 1
        left = self.max_attempts - self.attempts
        if left <= 0:
            self.attempts = 0
            self.cooldown_until = now + self.cooldown_seconds
            self.msg_label.config(
                text=self.tr.t("cooldown_msg", s=self.cooldown_seconds))
        else:
            self.msg_label.config(
                text=f"{self.tr.t('wrong_code')}  "
                     f"{self.tr.t('attempts_left', n=left)}")

    def _close(self):
        self._unhook_keys()
        if self.win is not None:
            try:
                self.win.grab_release()
            except tk.TclError:
                pass
            self.win.destroy()
            self.win = None

    def destroy(self):
        self._close()

    @property
    def visible(self):
        return self.win is not None
