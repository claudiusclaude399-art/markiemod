"""First-run setup wizard and the password-protected settings window."""

import tkinter as tk
from tkinter import messagebox, ttk

import autostart
import config as cfgmod
import security

BG = "#0f172a"
FG = "#e2e8f0"
ACCENT = "#2563eb"


def _label(parent, text):
    return tk.Label(parent, text=text, bg=BG, fg=FG,
                    font=("Segoe UI", 11), anchor="w")


def _entry(parent, show=None):
    e = tk.Entry(parent, bg="#1e293b", fg=FG, insertbackground=FG,
                 relief="flat", font=("Segoe UI", 11), show=show)
    e.configure(highlightthickness=1, highlightbackground="#334155")
    return e


def run_first_run_setup(root, cfg, tr):
    """Modal wizard. Returns the updated config dict, or None if cancelled."""
    win = tk.Toplevel(root)
    win.title(tr.t("setup_title"))
    win.configure(bg=BG)
    win.geometry("520x560")
    win.resizable(False, False)
    win.grab_set()

    recovery = security.generate_recovery_code()
    result = {"cfg": None}

    tk.Label(win, text=tr.t("app_name"), bg=BG, fg="#93c5fd",
             font=("Segoe UI", 18, "bold")).pack(pady=(18, 2))
    tk.Label(win, text=tr.t("setup_intro"), bg=BG, fg=FG,
             wraplength=470, font=("Segoe UI", 10)).pack(pady=(0, 12))

    body = tk.Frame(win, bg=BG)
    body.pack(fill="both", expand=True, padx=28)

    # Language
    _label(body, tr.t("language_label")).pack(fill="x", pady=(6, 0))
    lang_var = tk.StringVar(value=cfg.get("language", "en"))
    lang_frame = tk.Frame(body, bg=BG)
    lang_frame.pack(fill="x")
    tk.Radiobutton(lang_frame, text=tr.t("english"), variable=lang_var,
                   value="en", bg=BG, fg=FG, selectcolor="#1e293b",
                   activebackground=BG, font=("Segoe UI", 10)).pack(side="left")
    tk.Radiobutton(lang_frame, text=tr.t("slovenian"), variable=lang_var,
                   value="sl", bg=BG, fg=FG, selectcolor="#1e293b",
                   activebackground=BG, font=("Segoe UI", 10)).pack(side="left")

    _label(body, tr.t("set_admin_pw")).pack(fill="x", pady=(10, 0))
    admin_e = _entry(body, show="•"); admin_e.pack(fill="x", ipady=4)

    _label(body, tr.t("set_unlock_code")).pack(fill="x", pady=(10, 0))
    unlock_e = _entry(body, show="•"); unlock_e.pack(fill="x", ipady=4)

    limits = cfg.get("daily_limits", {}) or {}
    _label(body, tr.t("weekday_limit_label")).pack(fill="x", pady=(10, 0))
    weekday_e = _entry(body); weekday_e.insert(0, str(limits.get("mon", 120)))
    weekday_e.pack(fill="x", ipady=4)

    _label(body, tr.t("weekend_limit_label")).pack(fill="x", pady=(10, 0))
    weekend_e = _entry(body); weekend_e.insert(0, str(limits.get("sat", 240)))
    weekend_e.pack(fill="x", ipady=4)

    # Recovery code (read-only, shown prominently)
    tk.Label(body, text=tr.t("master_code_generated"), bg=BG, fg="#fbbf24",
             font=("Segoe UI", 10, "bold"), wraplength=460,
             justify="left").pack(fill="x", pady=(14, 2))
    rec = tk.Entry(body, bg="#1e293b", fg="#fbbf24", relief="flat",
                   font=("Consolas", 16, "bold"), justify="center",
                   readonlybackground="#1e293b")
    rec.insert(0, recovery)
    rec.configure(state="readonly")
    rec.pack(fill="x", ipady=8, pady=(0, 4))

    def finish():
        admin_pw = admin_e.get().strip()
        unlock = unlock_e.get().strip()
        if not admin_pw or not unlock:
            messagebox.showwarning(tr.t("setup_title"),
                                   tr.t("fields_required"), parent=win)
            return
        def _int(widget, default):
            try:
                return max(0, int(widget.get().strip() or "0"))
            except ValueError:
                return default
        wd = _int(weekday_e, 120)
        we = _int(weekend_e, 240)
        cfg["language"] = lang_var.get()
        cfg["admin_password"] = security.hash_code(admin_pw)
        cfg["unlock_code"] = security.hash_code(unlock)
        cfg["master_code"] = security.hash_code(recovery)
        cfg["daily_limits"] = {
            "mon": wd, "tue": wd, "wed": wd, "thu": wd, "fri": wd,
            "sat": we, "sun": we,
        }
        cfg["first_run"] = False
        result["cfg"] = cfg
        win.destroy()

    tk.Button(win, text=tr.t("finish_setup"), command=finish,
              bg=ACCENT, fg="#ffffff", relief="flat",
              font=("Segoe UI", 12, "bold"), padx=20, pady=8).pack(pady=14)

    win.protocol("WM_DELETE_WINDOW", lambda: None)  # must finish setup
    root.wait_window(win)
    return result["cfg"]


def open_settings(root, app):
    """Password gate, then the settings editor. `app` is the main App."""
    tr = app.tr
    cfg = app.config

    gate = tk.Toplevel(root)
    gate.title(tr.t("settings_title"))
    gate.configure(bg=BG)
    gate.geometry("360x180")
    gate.resizable(False, False)
    gate.grab_set()

    tk.Label(gate, text=tr.t("admin_password"), bg=BG, fg=FG,
             font=("Segoe UI", 12)).pack(pady=(26, 6))
    pw = _entry(gate, show="•"); pw.pack(ipady=5, padx=40, fill="x")
    pw.focus_set()
    err = tk.Label(gate, text="", bg=BG, fg="#f87171", font=("Segoe UI", 10))
    err.pack(pady=4)

    def check():
        if security.verify_code(pw.get(), cfg.get("admin_password")):
            gate.destroy()
            _editor(root, app)
        else:
            err.config(text=tr.t("wrong_password"))
            pw.delete(0, tk.END)

    pw.bind("<Return>", lambda e: check())
    tk.Button(gate, text=tr.t("login"), command=check, bg=ACCENT, fg="#ffffff",
              relief="flat", font=("Segoe UI", 11, "bold"),
              padx=18, pady=6).pack()


def _editor(root, app):
    tr = app.tr
    cfg = app.config

    win = tk.Toplevel(root)
    win.title(tr.t("settings_title"))
    win.configure(bg=BG)
    win.geometry("600x820")
    win.resizable(False, False)
    win.grab_set()

    canvas_frame = tk.Frame(win, bg=BG)
    canvas_frame.pack(fill="both", expand=True, padx=28, pady=16)

    tk.Label(canvas_frame, text=tr.t("settings_title"), bg=BG, fg="#93c5fd",
             font=("Segoe UI", 18, "bold")).pack(anchor="w", pady=(0, 12))

    # Language
    _label(canvas_frame, tr.t("language_label")).pack(fill="x")
    lang_var = tk.StringVar(value=cfg.get("language", "en"))
    lf = tk.Frame(canvas_frame, bg=BG); lf.pack(fill="x")
    tk.Radiobutton(lf, text=tr.t("english"), variable=lang_var, value="en",
                   bg=BG, fg=FG, selectcolor="#1e293b", activebackground=BG).pack(side="left")
    tk.Radiobutton(lf, text=tr.t("slovenian"), variable=lang_var, value="sl",
                   bg=BG, fg=FG, selectcolor="#1e293b", activebackground=BG).pack(side="left")

    # ---- per-day limits -------------------------------------------------
    limits = cfg.get("daily_limits", {}) or {}
    _label(canvas_frame, tr.t("per_day_limits_label")).pack(fill="x", pady=(10, 2))
    days_frame = tk.Frame(canvas_frame, bg=BG)
    days_frame.pack(fill="x")
    day_entries = {}
    for i, d in enumerate(cfgmod.DAYS):
        r, c = divmod(i, 2)
        cell = tk.Frame(days_frame, bg=BG)
        cell.grid(row=r, column=c, sticky="w", padx=(0, 18), pady=2)
        tk.Label(cell, text=tr.t("day_" + d) + ":", bg=BG, fg=FG, width=11,
                 anchor="w", font=("Segoe UI", 10)).pack(side="left")
        e = _entry(cell); e.configure(width=6)
        e.insert(0, str(limits.get(d, 120)))
        e.pack(side="left", ipady=2)
        day_entries[d] = e

    def _set_days(days, src):
        val = src.get().strip()
        for d in days:
            day_entries[d].delete(0, tk.END)
            day_entries[d].insert(0, val)

    quick = tk.Frame(canvas_frame, bg=BG)
    quick.pack(fill="x", pady=(6, 0))
    quick_e = _entry(quick); quick_e.configure(width=6)
    quick_e.insert(0, "120"); quick_e.pack(side="left", ipady=2)
    tk.Button(quick, text=tr.t("apply_weekday"),
              command=lambda: _set_days(cfgmod.WEEKDAYS, quick_e),
              bg="#334155", fg="#ffffff", relief="flat",
              font=("Segoe UI", 9)).pack(side="left", padx=6)
    tk.Button(quick, text=tr.t("apply_weekend"),
              command=lambda: _set_days(cfgmod.WEEKEND, quick_e),
              bg="#334155", fg="#ffffff", relief="flat",
              font=("Segoe UI", 9)).pack(side="left")

    _label(canvas_frame, tr.t("grace_label")).pack(fill="x", pady=(12, 0))
    grace_e = _entry(canvas_frame); grace_e.insert(0, str(cfg.get("grace_minutes", 10)))
    grace_e.pack(fill="x", ipady=4)

    _label(canvas_frame, tr.t("idle_timeout_label")).pack(fill="x", pady=(10, 0))
    idle_e = _entry(canvas_frame); idle_e.insert(0, str(cfg.get("idle_timeout_seconds", 60)))
    idle_e.pack(fill="x", ipady=4)

    _label(canvas_frame, tr.t("blocked_hours_label")).pack(fill="x", pady=(10, 0))
    windows_txt = ", ".join(f"{w['start']}-{w['end']}"
                            for w in cfg.get("blocked_windows", []))
    blocked_e = _entry(canvas_frame); blocked_e.insert(0, windows_txt)
    blocked_e.pack(fill="x", ipady=4)

    _label(canvas_frame, tr.t("unlock_code_label")).pack(fill="x", pady=(10, 0))
    unlock_e = _entry(canvas_frame, show="•"); unlock_e.pack(fill="x", ipady=4)

    _label(canvas_frame, tr.t("admin_pw_label")).pack(fill="x", pady=(10, 0))
    adminpw_e = _entry(canvas_frame, show="•"); adminpw_e.pack(fill="x", ipady=4)

    block_tm = tk.BooleanVar(value=cfg.get("block_task_manager", False))
    tk.Checkbutton(canvas_frame, text=tr.t("block_tm_label"), variable=block_tm,
                   bg=BG, fg=FG, selectcolor="#1e293b", activebackground=BG,
                   font=("Segoe UI", 10)).pack(anchor="w", pady=(12, 0))

    autostart_var = tk.BooleanVar(value=autostart.is_enabled() or cfg.get("autostart", True))
    tk.Checkbutton(canvas_frame, text=tr.t("autostart_label"), variable=autostart_var,
                   bg=BG, fg=FG, selectcolor="#1e293b", activebackground=BG,
                   font=("Segoe UI", 10)).pack(anchor="w")

    err = tk.Label(canvas_frame, text="", bg=BG, fg="#f87171", font=("Segoe UI", 10))
    err.pack(anchor="w", pady=(6, 0))

    def parse_windows(text):
        out = []
        text = text.strip()
        if not text:
            return out
        for part in text.split(","):
            part = part.strip()
            if not part:
                continue
            if "-" not in part:
                raise ValueError
            start, end = part.split("-", 1)
            start, end = start.strip(), end.strip()
            for hm in (start, end):
                hh, mm = hm.split(":")
                if not (0 <= int(hh) <= 23 and 0 <= int(mm) <= 59):
                    raise ValueError
            out.append({"start": start, "end": end})
        return out

    def save():
        try:
            windows = parse_windows(blocked_e.get())
        except (ValueError, IndexError):
            err.config(text=tr.t("invalid_time"))
            return
        try:
            new_limits = {}
            for d in cfgmod.DAYS:
                new_limits[d] = max(0, int(day_entries[d].get().strip() or "0"))
            cfg["daily_limits"] = new_limits
            cfg["grace_minutes"] = max(1, int(grace_e.get().strip() or "10"))
            cfg["idle_timeout_seconds"] = max(0, int(idle_e.get().strip() or "0"))
        except ValueError:
            err.config(text=tr.t("invalid_time"))
            return
        cfg["blocked_windows"] = windows
        cfg["block_task_manager"] = block_tm.get()
        cfg["language"] = lang_var.get()
        cfg["autostart"] = autostart_var.get()

        if unlock_e.get().strip():
            cfg["unlock_code"] = security.hash_code(unlock_e.get().strip())
        if adminpw_e.get().strip():
            cfg["admin_password"] = security.hash_code(adminpw_e.get().strip())

        if autostart_var.get():
            autostart.enable()
        else:
            autostart.disable()

        app.apply_config()
        messagebox.showinfo(tr.t("settings_title"), tr.t("saved"), parent=win)
        win.destroy()

    btns = tk.Frame(canvas_frame, bg=BG)
    btns.pack(fill="x", pady=(16, 0))
    tk.Button(btns, text=tr.t("save"), command=save, bg=ACCENT, fg="#ffffff",
              relief="flat", font=("Segoe UI", 12, "bold"),
              padx=20, pady=8).pack(side="left")
    tk.Button(btns, text=tr.t("quit_app"), command=lambda: app.request_quit(win),
              bg="#475569", fg="#ffffff", relief="flat",
              font=("Segoe UI", 11), padx=16, pady=8).pack(side="right")
