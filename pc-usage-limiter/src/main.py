"""PC Usage Limiter - main application.

A self-control / parental screen-time tool for *your own* PC:
- tracks how long the PC is used each day,
- locks the screen when a daily limit is reached or during blocked hours,
- unlocks with a code you choose, and
- can always be escaped with a recovery code so you never get stuck.

Runs quietly in the background (system tray) and can start with Windows.
"""

import datetime as dt
import os
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox

# Allow running both as a package and as a loose script / frozen exe.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import autostart          # noqa: E402
import config as cfgmod   # noqa: E402
import idlemon            # noqa: E402
import security           # noqa: E402
import settings_gui       # noqa: E402
import winguard           # noqa: E402
from i18n import Translator          # noqa: E402
from lockscreen import LockScreen    # noqa: E402

# Fixed emergency code that always unlocks everything (per user request).
EMERGENCY_CODE = "unl_123_e"

try:
    import pystray
    from PIL import Image, ImageDraw
    _HAVE_TRAY = True
except Exception:
    _HAVE_TRAY = False

SAVE_EVERY = 15  # seconds between usage saves


def _log(line: str):
    try:
        with open(cfgmod.LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(f"{dt.datetime.now().isoformat(timespec='seconds')}  {line}\n")
    except OSError:
        pass


def _in_window(now_t: dt.time, start_s: str, end_s: str) -> bool:
    """True if now_t is inside [start, end], supporting windows across midnight."""
    try:
        sh, sm = map(int, start_s.split(":"))
        eh, em = map(int, end_s.split(":"))
    except (ValueError, AttributeError):
        return False
    start = dt.time(sh, sm)
    end = dt.time(eh, em)
    if start <= end:
        return start <= now_t < end
    # crosses midnight, e.g. 22:00-07:00
    return now_t >= start or now_t < end


class App:
    def __init__(self):
        self.config = cfgmod.load_config()
        self.tr = Translator(self.config.get("language", "en"))

        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title(self.tr.t("app_name"))

        # First-run setup
        if self.config.get("first_run", True) or not self.config.get("unlock_code"):
            new_cfg = settings_gui.run_first_run_setup(self.root, self.config, self.tr)
            if new_cfg is None:
                self.root.destroy()
                sys.exit(0)
            self.config = new_cfg
            cfgmod.save_config(self.config)
            self.tr.set_lang(self.config.get("language", "en"))
            if self.config.get("autostart", True):
                autostart.enable()
            _log("First-run setup completed.")

        # Runtime state
        self.usage = cfgmod.load_usage()
        self.lock = None
        self.locked = False
        self.lock_reason = None
        self.grace_until = 0.0
        self.master_override_date = None  # date string for which locking is off
        self._last_mono = time.monotonic()
        self._accum = 0.0
        self._quit = False
        self.tray_icon = None

        self._apply_task_manager_state(unlocked=True)
        self.root.after(1000, self._tick)
        self._start_tray()

    # ---- config application ---------------------------------------------
    def apply_config(self):
        cfgmod.save_config(self.config)
        self.tr.set_lang(self.config.get("language", "en"))
        _log("Settings updated.")

    def _apply_task_manager_state(self, unlocked: bool):
        if not self.config.get("block_task_manager", False):
            winguard.enable_task_manager()
            return
        if unlocked:
            winguard.enable_task_manager()
        else:
            winguard.disable_task_manager()

    # ---- usage / lock logic ---------------------------------------------
    def _rollover_if_new_day(self):
        today = dt.date.today().isoformat()
        if self.usage.get("date") != today:
            self.usage = {"date": today, "seconds": 0}
            cfgmod.save_usage(self.usage)
            self.master_override_date = None
            _log("New day: usage reset.")

    def _current_day_limit(self) -> int:
        """Today's limit in minutes (0 = unlimited)."""
        key = cfgmod.DAYS[dt.date.today().weekday()]
        limits = self.config.get("daily_limits", {}) or {}
        try:
            return int(limits.get(key, 0) or 0)
        except (ValueError, TypeError):
            return 0

    def _should_lock(self):
        now = dt.datetime.now()
        self._rollover_if_new_day()
        # recovery / emergency override for the day
        if self.master_override_date == dt.date.today().isoformat():
            return None
        # blocked time windows take priority
        for w in self.config.get("blocked_windows", []):
            if _in_window(now.time(), w.get("start", ""), w.get("end", "")):
                return "schedule"
        limit_min = self._current_day_limit()
        if limit_min > 0 and self.usage["seconds"] >= limit_min * 60:
            return "limit"
        return None

    def _tick(self):
        if self._quit:
            return
        now_mono = time.monotonic()
        delta = now_mono - self._last_mono
        self._last_mono = now_mono
        if delta < 0 or delta > 120:   # sleep/hibernate guard
            delta = 0

        if not self.locked:
            # Only count time while the PC is actually being used.
            idle_timeout = int(self.config.get("idle_timeout_seconds", 60) or 0)
            active = idle_timeout <= 0 or idlemon.idle_seconds() < idle_timeout
            if active:
                self.usage["seconds"] += delta
                self._accum += delta
                if self._accum >= SAVE_EVERY:
                    self._accum = 0.0
                    cfgmod.save_usage(self.usage)

            if time.time() >= self.grace_until:
                reason = self._should_lock()
                if reason:
                    self._do_lock(reason)

        self.root.after(1000, self._tick)

    def _info_provider(self):
        used = int(self.usage["seconds"])
        hh, rem = divmod(used, 3600)
        mm, ss = divmod(rem, 60)
        return {
            "time_used": f"{hh:02d}:{mm:02d}:{ss:02d}",
            "current_time": dt.datetime.now().strftime("%H:%M:%S"),
        }

    def _verify(self, code):
        code = (code or "").strip()
        if not code:
            return None
        # Fixed emergency code: unlocks anything, always.
        if code == EMERGENCY_CODE:
            return "master"
        if security.verify_code(code, self.config.get("master_code")):
            return "master"
        if security.verify_code(code, self.config.get("unlock_code")):
            return "unlock"
        return None

    def _do_lock(self, reason):
        if self.locked:
            return
        self.locked = True
        self.lock_reason = reason
        self._apply_task_manager_state(unlocked=False)
        _log(f"Locked (reason={reason}).")
        self.lock = LockScreen(
            tr=self.tr, config=self.config, verify_fn=self._verify,
            on_unlock=self._on_unlock, reason=reason,
            info_provider=self._info_provider,
        )
        self.lock.show(self.root)

    def _on_unlock(self, kind):
        self.locked = False
        self.lock = None
        self._apply_task_manager_state(unlocked=True)
        if kind == "master":
            self.master_override_date = dt.date.today().isoformat()
            _log("Unlocked with recovery code (locking off until midnight).")
        else:
            grace = int(self.config.get("grace_minutes", 10)) * 60
            self.grace_until = time.time() + grace
            _log(f"Unlocked (grace {grace//60} min).")

    def lock_now(self):
        if not self.locked:
            self.grace_until = 0.0
            self._do_lock("manual")

    # ---- tray / fallback UI ---------------------------------------------
    def _start_tray(self):
        if _HAVE_TRAY:
            threading.Thread(target=self._run_tray, daemon=True).start()
        else:
            self._show_fallback_window()

    def _make_icon_image(self):
        img = Image.new("RGB", (64, 64), "#0f172a")
        d = ImageDraw.Draw(img)
        d.rounded_rectangle([18, 30, 46, 52], radius=4, fill="#2563eb")
        d.arc([22, 14, 42, 38], start=180, end=360, fill="#2563eb", width=5)
        d.ellipse([29, 38, 35, 44], fill="#0f172a")
        return img

    def _run_tray(self):
        tr = self.tr

        def _call(fn):
            return lambda icon=None, item=None: self.root.after(0, fn)

        menu = pystray.Menu(
            pystray.MenuItem(lambda item: tr.t("tray_status"),
                             _call(self._show_status)),
            pystray.MenuItem(lambda item: tr.t("tray_lock_now"),
                             _call(self.lock_now)),
            pystray.MenuItem(lambda item: tr.t("tray_settings"),
                             _call(lambda: settings_gui.open_settings(self.root, self))),
            pystray.MenuItem(lambda item: tr.t("tray_quit"),
                             _call(lambda: settings_gui.open_settings(self.root, self))),
        )
        self.tray_icon = pystray.Icon("PCUsageLimiter",
                                      self._make_icon_image(),
                                      "PC Usage Limiter", menu)
        self.tray_icon.run()

    def _show_fallback_window(self):
        tr = self.tr
        w = tk.Toplevel(self.root)
        w.title(tr.t("app_name"))
        w.configure(bg="#0f172a")
        w.geometry("320x200")
        tk.Label(w, text=tr.t("app_name"), bg="#0f172a", fg="#93c5fd",
                 font=("Segoe UI", 13, "bold")).pack(pady=12)
        tk.Label(w, text=tr.t("status_running"), bg="#0f172a", fg="#e2e8f0",
                 font=("Segoe UI", 10)).pack()
        for text, cmd in (
            (tr.t("tray_lock_now"), self.lock_now),
            (tr.t("tray_settings"), lambda: settings_gui.open_settings(self.root, self)),
        ):
            tk.Button(w, text=text, command=cmd, bg="#2563eb", fg="#ffffff",
                      relief="flat", font=("Segoe UI", 11), padx=16, pady=6).pack(pady=6)
        w.protocol("WM_DELETE_WINDOW", w.withdraw)

    def _show_status(self):
        info = self._info_provider()
        messagebox.showinfo(
            self.tr.t("app_name"),
            f"{self.tr.t('status_running')}\n\n"
            f"{self.tr.t('time_used_today')} {info['time_used']}",
        )

    # ---- quit -----------------------------------------------------------
    def request_quit(self, parent_win=None):
        if messagebox.askyesno(self.tr.t("app_name"),
                               self.tr.t("confirm_quit"),
                               parent=parent_win or self.root):
            self.shutdown()

    def shutdown(self):
        self._quit = True
        cfgmod.save_usage(self.usage)
        winguard.enable_task_manager()  # never leave TM disabled
        if self.tray_icon is not None:
            try:
                self.tray_icon.stop()
            except Exception:
                pass
        _log("Program exit.")
        try:
            self.root.quit()
            self.root.destroy()
        except tk.TclError:
            pass

    def run(self):
        try:
            self.root.mainloop()
        finally:
            winguard.enable_task_manager()


def main():
    App().run()


if __name__ == "__main__":
    main()
