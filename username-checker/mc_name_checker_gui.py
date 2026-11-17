"""
Minecraft Username Checker - Windows 10 style GUI.

Green = available, red = taken. Available names are saved to available.txt
next to the program; every checked name goes into checked_names.db so it can
be searched later.
"""

import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import messagebox, ttk
import tkinter.font as tkfont

import json

import checker_core as core
import history
import notify

APP_NAME = "Minecraft Username Checker"

# Windows 10 colours
WIN_GREEN = "#107C10"
WIN_RED = "#E81123"
WIN_BLUE = "#0078D7"
WIN_GREY = "#767676"
WIN_BG = "#FFFFFF"
WIN_PANEL = "#F2F2F2"
WIN_SUCCESS_BG = "#DFF6DD"      # Windows "success" info bar
WIN_SUCCESS_EDGE = "#9FD89F"
WIN_LOCKED_FG = "#8A6A00"       # dark gold text - readable "yellow" on white
WIN_LOCKED_BG = "#FFF4CE"       # Windows "warning" yellow background

MAX_LOG_LINES = 3000

STATUS_TEXT = {history.TAKEN: "taken", history.FREE: "AVAILABLE",
               history.BLOCKED: "LOCKED - blocked", history.HELD: "LOCKED - on hold"}
STATUS_TAG = {history.TAKEN: "taken", history.FREE: "free",
              history.BLOCKED: "locked", history.HELD: "locked"}
VERDICT_STATUS = {core.CLAIMABLE: history.FREE, core.UNVERIFIED: history.FREE,
                  core.BLOCKED: history.BLOCKED, core.HELD: history.HELD}
LOCK_REASON = {core.BLOCKED: "blocked by Mojang",
               core.HELD: "on hold (recently changed) or just taken"}
SEARCH_LIMIT = 500


def app_dir():
    """Folder of the .exe (or of this script) - where our files go."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def settings_path():
    return os.path.join(app_dir(), "settings.json")


def load_settings():
    try:
        with open(settings_path(), encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def save_settings(data):
    try:
        with open(settings_path(), "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)
    except OSError:
        pass


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


def enable_dpi_awareness():
    """Sharp text on high-DPI screens (otherwise Windows blurs the window)."""
    if os.name != "nt":
        return
    try:
        import ctypes
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            ctypes.windll.user32.SetProcessDPIAware()
    except Exception:
        pass


def fmt_count(n):
    return f"{n:,}" if n < 10 ** 15 else f"{n:.2e}"


def time_ago(t):
    d = time.time() - t
    if d < 60:
        return "just now"
    if d < 3600:
        return f"{int(d // 60)} min ago"
    if d < 86400:
        return f"{int(d // 3600)} h ago"
    return time.strftime("%d %b %Y", time.localtime(t))


# ---------------------------------------------------------------------- notifications

def windows_toast(title, body):
    """Show a Windows 10 notification (via the built-in PowerShell, no extra installs)."""
    if os.name != "nt":
        return
    title = title.replace("'", "''")
    body = body.replace("'", "''")
    script = (
        "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications,"
        " ContentType = WindowsRuntime] > $null;"
        "$x = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent("
        "[Windows.UI.Notifications.ToastTemplateType]::ToastText02);"
        "$t = $x.GetElementsByTagName('text');"
        "$t.Item(0).AppendChild($x.CreateTextNode('" + title + "')) > $null;"
        "$t.Item(1).AppendChild($x.CreateTextNode('" + body + "')) > $null;"
        "$n = [Windows.UI.Notifications.ToastNotification]::new($x);"
        "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("
        "'{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe'"
        ").Show($n)"
    )
    try:
        subprocess.Popen(["powershell.exe", "-NoProfile", "-NonInteractive",
                          "-ExecutionPolicy", "Bypass", "-Command", script],
                         creationflags=0x08000000)           # CREATE_NO_WINDOW
    except Exception:
        pass


def play_alert_sound(root):
    if os.name == "nt":
        try:
            import winsound
            winsound.MessageBeep(winsound.MB_ICONASTERISK)
            return
        except Exception:
            pass
    root.bell()


def flash_taskbar(root):
    """Make the taskbar button blink orange until the window is opened."""
    if os.name != "nt":
        return
    try:
        import ctypes

        class FLASHWINFO(ctypes.Structure):
            _fields_ = [("cbSize", ctypes.c_uint), ("hwnd", ctypes.c_void_p),
                        ("dwFlags", ctypes.c_uint), ("uCount", ctypes.c_uint),
                        ("dwTimeout", ctypes.c_uint)]

        hwnd = ctypes.windll.user32.GetParent(root.winfo_id())
        info = FLASHWINFO(ctypes.sizeof(FLASHWINFO), hwnd, 3 | 12, 0, 0)  # ALL | TIMERNOFG
        ctypes.windll.user32.FlashWindowEx(ctypes.byref(info))
    except Exception:
        pass


# ---------------------------------------------------------------------- app

class App:
    def __init__(self, root, selftest=None):
        self.root = root
        self.selftest = selftest
        self.checker = None
        self.events = queue.Queue()
        self.available_names = []
        self.out_path = os.path.join(app_dir(), "available.txt")
        self.db_path = os.path.join(app_dir(), "checked_names.db")
        self.out_file = None
        self.total = 0
        self.skipped = 0
        self.history = history.History(self.db_path)

        self.alert_hits = []            # short names shown in the banner
        self.alert_verified = {}        # name -> True (lock-checked free) / False (not checked)
        self.pending_discord = []
        self.last_discord = 0.0
        self.discord_dialog = None
        self.settings = load_settings()
        self.webhook = self.settings.get("discord_webhook") or None
        self.token = None               # Minecraft access token (memory only, never saved)
        self.token_owner = None
        self.lock_checker = None
        self.login_dialog = None
        self.n_free = 0
        self.n_locked = 0
        self.pending_toast = []
        self.last_toast = 0.0
        self.stopped_by_alert = None

        self._search_id = 0
        self._search_after = None
        self._last_search = 0.0
        self._search_busy = False

        self._setup_style()
        self._build()
        self._update_estimate()
        self.root.after(100, self._poll)

    # ------------------------------------------------------------------ look
    def _setup_style(self):
        root = self.root
        root.title(APP_NAME)
        root.configure(bg=WIN_BG)
        try:
            root.iconbitmap(resource("icon.ico"))
        except Exception:
            pass

        self.style = style = ttk.Style(root)
        for theme in ("vista", "winnative", "clam"):
            if theme in style.theme_names():
                style.theme_use(theme)
                break

        families = tkfont.families(root)
        family = "Segoe UI" if "Segoe UI" in families else tkfont.nametofont(
            "TkDefaultFont").actual("family")
        mono = ("Consolas" if "Consolas" in families
                else tkfont.nametofont("TkFixedFont").actual("family"))
        for fname in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(fname).configure(family=family, size=9)
            except tk.TclError:
                pass
        self.font_family = family
        self.font_title = (family, 16)
        self.font_big = (family, 14)
        self.font_bold = (family, 10, "bold")
        self.font_mono = (mono, 10)

        style.configure("TFrame", background=WIN_BG)
        style.configure("TLabel", background=WIN_BG)
        style.configure("TCheckbutton", background=WIN_BG)
        style.configure("TRadiobutton", background=WIN_BG)
        style.configure("TLabelframe", background=WIN_BG)
        style.configure("TLabelframe.Label", background=WIN_BG, foreground=WIN_BLUE)
        style.configure("TNotebook", background=WIN_BG)
        style.configure("TNotebook.Tab", padding=(12, 4))
        style.configure("Title.TLabel", font=self.font_title, background=WIN_BG)
        style.configure("Sub.TLabel", foreground=WIN_GREY, background=WIN_BG)
        style.configure("Hint.TLabel", foreground=WIN_GREY, background=WIN_BG)
        style.configure("Status.TFrame", background=WIN_PANEL)
        style.configure("Status.TLabel", background=WIN_PANEL, foreground="#333333")
        style.configure("StatNum.TLabel", font=self.font_big, background=WIN_BG)
        style.configure("Green.StatNum.TLabel", foreground=WIN_GREEN)
        style.configure("Red.StatNum.TLabel", foreground=WIN_RED)
        style.configure("Gold.StatNum.TLabel", foreground=WIN_LOCKED_FG)
        style.configure("Locked.TLabel", foreground=WIN_LOCKED_FG, font=self.font_bold)
        style.configure("LoginOk.TLabel", foreground=WIN_GREEN)
        style.configure("LoginBad.TLabel", foreground=WIN_RED)
        style.configure("Blue.StatNum.TLabel", foreground=WIN_BLUE)
        style.configure("StatCap.TLabel", foreground=WIN_GREY, background=WIN_BG)
        style.configure("Free.TLabel", foreground=WIN_GREEN, font=self.font_bold)
        style.configure("Taken.TLabel", foreground=WIN_RED, font=self.font_bold)
        style.configure("Accent.TButton", padding=(14, 4))
        style.configure("TButton", padding=(10, 3))
        style.configure("Treeview", rowheight=22, font=self.font_mono)
        style.configure("Treeview.Heading", font=(family, 9))

    # ------------------------------------------------------------------ layout
    def _build(self):
        root = self.root
        root.columnconfigure(0, weight=1)
        root.rowconfigure(4, weight=1)

        # header
        head = ttk.Frame(root, padding=(16, 12, 16, 4))
        head.grid(row=0, column=0, sticky="ew")
        ttk.Label(head, text=APP_NAME, style="Title.TLabel").pack(side="left")
        ttk.Label(head, text="Green = available,  yellow = locked,  red = taken",
                  style="Sub.TLabel").pack(side="left", padx=(14, 0), pady=(8, 0))

        self._build_settings(row=1)
        self._build_banner(row=2)
        self._build_buttons(row=3)

        # progress + tabs
        body = ttk.Frame(root, padding=(16, 4, 16, 8))
        body.grid(row=4, column=0, sticky="nsew")
        body.columnconfigure(0, weight=1)
        body.rowconfigure(1, weight=1)
        self.progress = ttk.Progressbar(body, mode="determinate", maximum=1000)
        self.progress.grid(row=0, column=0, sticky="ew", pady=(0, 8))

        self.tabs = ttk.Notebook(body)
        self.tabs.grid(row=1, column=0, sticky="nsew")
        self.tab_live = ttk.Frame(self.tabs, padding=6)
        self.tab_search = ttk.Frame(self.tabs, padding=6)
        self.tabs.add(self.tab_live, text="Live results")
        self.tabs.add(self.tab_search, text="Search checked names")
        self.tabs.bind("<<NotebookTabChanged>>", lambda e: self._tab_changed())
        self._build_live_tab(self.tab_live)
        self._build_search_tab(self.tab_search)

        # status bar
        ttk.Separator(root, orient="horizontal").grid(row=5, column=0, sticky="ew")
        status = ttk.Frame(root, style="Status.TFrame", padding=(10, 3))
        status.grid(row=6, column=0, sticky="ew")
        self.lbl_status = ttk.Label(status, text="Ready", style="Status.TLabel")
        self.lbl_status.pack(side="left")
        ttk.Label(status, text="Available names are saved to available.txt next to this program",
                  style="Status.TLabel").pack(side="right")

        self._settings_widgets = [self.sp_min, self.sp_max, self.cb_letters, self.cb_digits,
                                  self.cb_under, self.rb_seq, self.rb_rand, self.e_start,
                                  self.sp_threads]
        root.bind("<F5>", lambda e: self.start())
        root.bind("<Escape>", lambda e: self.stop())
        root.bind("<Control-f>", lambda e: self.focus_search())
        root.protocol("WM_DELETE_WINDOW", self.on_close)

    def _build_settings(self, row):
        top = ttk.Frame(self.root, padding=(16, 6, 16, 0))
        top.grid(row=row, column=0, sticky="ew")
        top.columnconfigure(0, weight=3)
        top.columnconfigure(1, weight=2)

        # --- search settings
        s = ttk.LabelFrame(top, text=" Search ", padding=(12, 6))
        s.grid(row=0, column=0, rowspan=3, sticky="nsew", padx=(0, 8))

        self.v_min = tk.IntVar(value=3)
        self.v_max = tk.IntVar(value=4)
        self.v_letters = tk.BooleanVar(value=True)
        self.v_digits = tk.BooleanVar(value=True)
        self.v_under = tk.BooleanVar(value=False)
        self.v_random = tk.BooleanVar(value=False)
        self.v_start = tk.StringVar()

        ttk.Label(s, text="Username length:").grid(row=0, column=0, sticky="w", pady=3)
        lf = ttk.Frame(s)
        lf.grid(row=0, column=1, sticky="w")
        self.sp_min = ttk.Spinbox(lf, from_=core.MIN_LEN, to=core.MAX_LEN, width=4,
                                  textvariable=self.v_min, command=self._lengths_changed)
        self.sp_min.pack(side="left")
        ttk.Label(lf, text="  to  ").pack(side="left")
        self.sp_max = ttk.Spinbox(lf, from_=core.MIN_LEN, to=core.MAX_LEN, width=4,
                                  textvariable=self.v_max, command=self._lengths_changed)
        self.sp_max.pack(side="left")
        ttk.Label(lf, text="  characters (3 - 16)", style="Hint.TLabel").pack(side="left")
        for sp in (self.sp_min, self.sp_max):
            sp.bind("<FocusOut>", lambda e: self._lengths_changed())
            sp.bind("<Return>", lambda e: self._lengths_changed())

        ttk.Label(s, text="Characters:").grid(row=1, column=0, sticky="w", pady=3)
        cf = ttk.Frame(s)
        cf.grid(row=1, column=1, sticky="w")
        self.cb_letters = ttk.Checkbutton(cf, text="Letters a-z", variable=self.v_letters,
                                          command=self._update_estimate)
        self.cb_digits = ttk.Checkbutton(cf, text="Numbers 0-9", variable=self.v_digits,
                                         command=self._update_estimate)
        self.cb_under = ttk.Checkbutton(cf, text="Underscores _", variable=self.v_under,
                                        command=self._update_estimate)
        for w in (self.cb_letters, self.cb_digits, self.cb_under):
            w.pack(side="left", padx=(0, 12))

        ttk.Label(s, text="Order:").grid(row=2, column=0, sticky="w", pady=3)
        of = ttk.Frame(s)
        of.grid(row=2, column=1, sticky="w")
        self.rb_seq = ttk.Radiobutton(of, text="In order (aaa, aab, aac ...)", value=False,
                                      variable=self.v_random, command=self._order_changed)
        self.rb_rand = ttk.Radiobutton(of, text="Random", value=True,
                                       variable=self.v_random, command=self._order_changed)
        self.rb_seq.pack(side="left", padx=(0, 12))
        self.rb_rand.pack(side="left")

        ttk.Label(s, text="Start from:").grid(row=3, column=0, sticky="w", pady=3)
        sf = ttk.Frame(s)
        sf.grid(row=3, column=1, sticky="w")
        self.e_start = ttk.Entry(sf, textvariable=self.v_start, width=18)
        self.e_start.pack(side="left")
        ttk.Label(sf, text="  optional - filled in when you press Stop",
                  style="Hint.TLabel").pack(side="left")

        self.lbl_estimate = ttk.Label(s, text="", style="Hint.TLabel")
        self.lbl_estimate.grid(row=4, column=0, columnspan=2, sticky="w", pady=(4, 0))

        # --- speed
        o = ttk.LabelFrame(top, text=" Speed ", padding=(12, 4))
        o.grid(row=0, column=1, sticky="nsew")
        self.v_threads = tk.IntVar(value=12)
        self.v_show_taken = tk.BooleanVar(value=True)
        ttk.Label(o, text="Parallel requests:").grid(row=0, column=0, sticky="w", pady=2)
        self.sp_threads = ttk.Spinbox(o, from_=1, to=64, width=5, textvariable=self.v_threads)
        self.sp_threads.grid(row=0, column=1, sticky="w", padx=(6, 0))
        ttk.Checkbutton(o, text="Show taken names", variable=self.v_show_taken
                        ).grid(row=0, column=2, sticky="w", padx=(16, 0))

        # --- short-name alerts
        a = ttk.LabelFrame(top, text=" Short-name alerts ", padding=(12, 4))
        a.grid(row=1, column=1, sticky="nsew", pady=(6, 0))
        self.v_alert_on = tk.BooleanVar(value=True)
        self.v_alert_mode = tk.StringVar(value="up to")
        self.v_alert_len = tk.IntVar(value=3)
        self.v_alert_unlocked = tk.BooleanVar(value=False)
        self.v_alert_notify = tk.BooleanVar(value=True)
        self.v_alert_stop = tk.BooleanVar(value=False)
        self.v_discord = tk.BooleanVar(value=bool(self.webhook) and self.settings.get("discord_on", True))
        self.v_everyone = tk.BooleanVar(value=bool(self.settings.get("discord_everyone", False)))
        a.columnconfigure(1, weight=1)
        af = ttk.Frame(a)
        af.grid(row=0, column=0, columnspan=2, sticky="w")
        ttk.Checkbutton(af, text="Alert for free names", variable=self.v_alert_on
                        ).pack(side="left")
        ttk.Combobox(af, textvariable=self.v_alert_mode, values=("up to", "exactly"),
                     state="readonly", width=7).pack(side="left", padx=(4, 0))
        self.sp_alert = ttk.Spinbox(af, from_=core.MIN_LEN, to=core.MAX_LEN, width=3,
                                    textvariable=self.v_alert_len)
        self.sp_alert.pack(side="left", padx=4)
        ttk.Label(af, text="letters").pack(side="left")
        ttk.Checkbutton(a, text="Only unlocked names", variable=self.v_alert_unlocked,
                        command=self._only_unlocked_toggled
                        ).grid(row=1, column=0, sticky="w", padx=(20, 0), pady=1)
        ttk.Checkbutton(a, text="Stop when found", variable=self.v_alert_stop
                        ).grid(row=1, column=1, sticky="w", padx=(12, 0), pady=1)
        ttk.Checkbutton(a, text="Notification + sound", variable=self.v_alert_notify
                        ).grid(row=2, column=0, sticky="w", padx=(20, 0), pady=1)
        df = ttk.Frame(a)
        df.grid(row=2, column=1, sticky="w", padx=(12, 0))
        ttk.Checkbutton(df, text="Discord", variable=self.v_discord, command=self._discord_toggled
                        ).pack(side="left")
        ttk.Button(df, text="Webhook...", command=self.open_discord, padding=(4, 0)
                   ).pack(side="left", padx=(4, 0))

        # --- locked names
        k = ttk.LabelFrame(top, text=" Locked names ", padding=(12, 4))
        k.grid(row=2, column=1, sticky="nsew", pady=(6, 0))
        self.v_lockcheck = tk.BooleanVar(value=False)
        ttk.Checkbutton(k, text="Check that free names aren't locked (yellow)",
                        variable=self.v_lockcheck, command=self._lockcheck_toggled
                        ).grid(row=0, column=0, columnspan=2, sticky="w")
        ttk.Button(k, text="Log in...", command=self.open_login, padding=(6, 1)
                   ).grid(row=1, column=0, sticky="w", padx=(20, 0), pady=(2, 1))
        self.lbl_login = ttk.Label(k, text="Needs your Minecraft login", style="Hint.TLabel")
        self.lbl_login.grid(row=1, column=1, sticky="w", padx=(8, 0))

    def _build_banner(self, row):
        self.banner = tk.Frame(self.root, bg=WIN_SUCCESS_BG, highlightthickness=1,
                               highlightbackground=WIN_SUCCESS_EDGE)
        self.banner_row = row
        inner = tk.Frame(self.banner, bg=WIN_SUCCESS_BG)
        inner.pack(fill="x", padx=10, pady=6)
        tk.Label(inner, text="✔", bg=WIN_SUCCESS_BG, fg=WIN_GREEN,
                 font=(self.font_family, 12, "bold")).pack(side="left")
        self.banner_text = tk.Label(inner, text="", bg=WIN_SUCCESS_BG, fg="#0B5A0B",
                                    font=(self.font_family, 10, "bold"), anchor="w")
        self.banner_text.pack(side="left", padx=(8, 0), fill="x", expand=True)
        ttk.Button(inner, text="Dismiss", command=self.dismiss_banner).pack(side="right")
        ttk.Button(inner, text="Copy", command=self.copy_banner).pack(side="right", padx=6)

    def _build_buttons(self, row):
        bar = ttk.Frame(self.root, padding=(16, 10, 16, 4))
        bar.grid(row=row, column=0, sticky="ew")
        self.btn_start = ttk.Button(bar, text="Start", style="Accent.TButton", command=self.start)
        self.btn_stop = ttk.Button(bar, text="Stop", command=self.stop, state="disabled")
        self.btn_clear = ttk.Button(bar, text="Clear", command=self.clear)
        self.btn_start.pack(side="left")
        self.btn_stop.pack(side="left", padx=6)
        self.btn_clear.pack(side="left")

        stats = ttk.Frame(bar)
        stats.pack(side="right")
        self.stat_labels = {}
        for key, cap, st in (("checked", "Checked", "StatNum.TLabel"),
                             ("available", "Available", "Green.StatNum.TLabel"),
                             ("locked", "Locked", "Gold.StatNum.TLabel"),
                             ("taken", "Taken", "Red.StatNum.TLabel"),
                             ("speed", "Names / sec", "Blue.StatNum.TLabel")):
            f = ttk.Frame(stats)
            f.pack(side="left", padx=(18, 0))
            lbl = ttk.Label(f, text="0", style=st)
            lbl.pack(anchor="e")
            cap_lbl = ttk.Label(f, text=cap, style="StatCap.TLabel")
            cap_lbl.pack(anchor="e")
            self.stat_labels[key] = lbl
            if key == "locked":
                self.lbl_locked_cap = cap_lbl

    def _build_live_tab(self, tab):
        tab.columnconfigure(0, weight=3)
        tab.columnconfigure(1, weight=1)
        tab.rowconfigure(0, weight=1)

        lf = ttk.LabelFrame(tab, text=" Results ", padding=4)
        lf.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        lf.rowconfigure(0, weight=1)
        lf.columnconfigure(0, weight=1)
        self.txt = tk.Text(lf, font=self.font_mono, bg=WIN_BG, relief="flat", borderwidth=0,
                           highlightthickness=0, wrap="none", state="disabled", height=10,
                           cursor="arrow")
        sb = ttk.Scrollbar(lf, orient="vertical",
                           command=lambda *a: (self.txt.yview(*a), self._log_scrolled()))
        self.txt.configure(yscrollcommand=sb.set)
        self.txt.grid(row=0, column=0, sticky="nsew")
        # the log follows new results unless you scroll up; scrolling back down resumes it
        self._follow_log = True
        for seq in ("<MouseWheel>", "<Button-4>", "<Button-5>"):
            self.txt.bind(seq, lambda e: self._log_scrolled(), add="+")
        sb.grid(row=0, column=1, sticky="ns")
        self.txt.tag_configure("free", foreground=WIN_GREEN, font=(self.font_mono[0], 10, "bold"))
        self.txt.tag_configure("alert", foreground=WIN_GREEN, background=WIN_SUCCESS_BG,
                               font=(self.font_mono[0], 10, "bold"))
        self.txt.tag_configure("taken", foreground=WIN_RED)
        self.txt.tag_configure("locked", foreground=WIN_LOCKED_FG, background=WIN_LOCKED_BG,
                               font=(self.font_mono[0], 10, "bold"))
        self.txt.tag_configure("info", foreground=WIN_GREY)

        af = ttk.LabelFrame(tab, text=" Available names ", padding=4)
        af.grid(row=0, column=1, sticky="nsew")
        af.rowconfigure(0, weight=1)
        af.columnconfigure(0, weight=1)
        self.lst = tk.Listbox(af, font=(self.font_mono[0], 11, "bold"), fg=WIN_GREEN, bg=WIN_BG,
                              relief="flat", borderwidth=0, highlightthickness=0,
                              selectbackground=WIN_BLUE, selectforeground="white",
                              activestyle="none", selectmode="extended", width=24)
        sb2 = ttk.Scrollbar(af, orient="vertical", command=self.lst.yview)
        self.lst.configure(yscrollcommand=sb2.set)
        self.lst.grid(row=0, column=0, sticky="nsew")
        sb2.grid(row=0, column=1, sticky="ns")
        bf = ttk.Frame(af)
        bf.grid(row=1, column=0, columnspan=2, sticky="ew", pady=(4, 0))
        for i, (txt, cmd) in enumerate((("Copy", self.copy_selected), ("Copy all", self.copy_all),
                                        ("Open file", self.open_file))):
            bf.columnconfigure(i, weight=1)
            ttk.Button(bf, text=txt, command=cmd, padding=(4, 3)).grid(
                row=0, column=i, sticky="ew", padx=(0 if i == 0 else 4, 0))
        self.lst.bind("<Control-c>", lambda e: self.copy_selected())

    def _build_search_tab(self, tab):
        tab.columnconfigure(0, weight=1)
        tab.rowconfigure(2, weight=1)

        row = ttk.Frame(tab)
        row.grid(row=0, column=0, sticky="ew")
        ttk.Label(row, text="Search:").pack(side="left")
        self.v_search = tk.StringVar()
        self.e_search = ttk.Entry(row, textvariable=self.v_search, width=24, font=self.font_mono)
        self.e_search.pack(side="left", padx=(6, 12))
        self.v_search.trace_add("write", lambda *a: self._schedule_search())
        self.e_search.bind("<Return>", lambda e: self._search_enter())
        self.v_filter = tk.StringVar(value="all")
        for text, val in (("All", "all"), ("Available", "free"), ("Locked", "locked"),
                          ("Taken", "taken")):
            ttk.Radiobutton(row, text=text, value=val, variable=self.v_filter,
                            command=self.run_search).pack(side="left", padx=(0, 10))
        ttk.Button(row, text="Refresh", command=self.run_search).pack(side="left", padx=(6, 0))
        self.lbl_matches = ttk.Label(row, text="", style="Hint.TLabel")
        self.lbl_matches.pack(side="right")

        info = ttk.Frame(tab)
        info.grid(row=1, column=0, sticky="ew", pady=(8, 6))
        self.lbl_lookup = ttk.Label(info, text="Type part of a name, e.g.  ab  finds abc, xab9 ...",
                                    style="Hint.TLabel")
        self.lbl_lookup.pack(side="left")
        self.btn_check_now = ttk.Button(info, text="Check now", command=self.check_now)
        self.btn_check_now.pack(side="left", padx=(10, 0))
        self.btn_check_now.pack_forget()

        tf = ttk.Frame(tab)
        tf.grid(row=2, column=0, sticky="nsew")
        tf.rowconfigure(0, weight=1)
        tf.columnconfigure(0, weight=1)
        self.tree = ttk.Treeview(tf, columns=("name", "status", "when"), show="headings",
                                 selectmode="extended")
        for col, text, width, anchor in (("name", "Name", 200, "w"),
                                         ("status", "Status", 140, "w"),
                                         ("when", "Checked", 140, "w")):
            self.tree.heading(col, text=text, anchor=anchor)
            self.tree.column(col, width=width, anchor=anchor, stretch=(col == "name"))
        self.tree.tag_configure("free", foreground=WIN_GREEN)
        self.tree.tag_configure("taken", foreground=WIN_RED)
        self.tree.tag_configure("locked", foreground=WIN_LOCKED_FG, background=WIN_LOCKED_BG)
        sb = ttk.Scrollbar(tf, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.tree.bind("<Control-c>", lambda e: self.copy_tree_selection())

        bottom = ttk.Frame(tab)
        bottom.grid(row=3, column=0, sticky="ew", pady=(6, 0))
        ttk.Button(bottom, text="Copy selected", command=self.copy_tree_selection).pack(side="left")
        ttk.Button(bottom, text="Forget all checked names", command=self.forget_history
                   ).pack(side="left", padx=6)
        self.lbl_totals = ttk.Label(bottom, text="", style="Hint.TLabel")
        self.lbl_totals.pack(side="right")

    # ------------------------------------------------------------------ settings
    def _int(self, var, default):
        try:
            return int(var.get())
        except (tk.TclError, ValueError):
            return default

    def _lengths_changed(self):
        lo = max(core.MIN_LEN, min(core.MAX_LEN, self._int(self.v_min, 3)))
        hi = max(core.MIN_LEN, min(core.MAX_LEN, self._int(self.v_max, 4)))
        if hi < lo:
            hi = lo
        self.v_min.set(lo)
        self.v_max.set(hi)
        self._update_estimate()

    def _order_changed(self):
        self.e_start.configure(state="disabled" if self.v_random.get() else "normal")
        self._update_estimate()

    def _charset(self):
        return core.build_charset(self.v_letters.get(), self.v_digits.get(), self.v_under.get())

    def _update_estimate(self):
        cs = self._charset()
        if not cs:
            self.lbl_estimate.configure(text="Pick at least one kind of character.")
            return
        lo, hi = self._int(self.v_min, 3), self._int(self.v_max, 4)
        total = core.count_names(lo, max(lo, hi), cs)
        self.lbl_estimate.configure(text=f"{fmt_count(total)} possible names to check")

    def _set_settings_state(self, enabled):
        for w in self._settings_widgets:
            w.configure(state="normal" if enabled else "disabled")
        if enabled:
            self._order_changed()

    # ------------------------------------------------------------------ actions
    def start(self):
        if self.checker and self.checker.is_running():
            return
        self._lengths_changed()
        cs = self._charset()
        if not cs:
            messagebox.showwarning(APP_NAME, "Tick at least one of letters, numbers or underscores.")
            return
        lo, hi = self.v_min.get(), self.v_max.get()
        start = self.v_start.get().strip().lower() or None
        if start and not self.v_random.get():
            bad = [c for c in start if c not in cs]
            if bad or not (lo <= len(start) <= hi):
                messagebox.showwarning(
                    APP_NAME, f"'Start from' must be {lo}-{hi} characters long and only use the "
                              f"characters you ticked.")
                return
        else:
            start = None

        self.total = core.count_names(lo, hi, cs)
        self.skipped = 0
        if start:
            # names before `start` are skipped - count them so the progress bar is right
            for n in range(lo, len(start)):
                self.skipped += len(cs) ** n
            idx = 0
            for ch in start:
                idx = idx * len(cs) + cs.index(ch)
            self.skipped += idx
        self.progress.configure(value=0)
        self.stopped_by_alert = None
        self.n_free = self.n_locked = 0
        self.lock_checker = None
        if self.v_lockcheck.get() and self.token:
            self._start_lock_checker()

        names = core.generate_names(lo, hi, cs, start=start, shuffle=self.v_random.get())
        self.checker = core.Checker(
            names, threads=self._int(self.v_threads, 12), charset=cs,
            on_result=lambda n, a: self.events.put(("result", n, a)),
            on_status=lambda s: self.events.put(("status", s)),
        )
        try:
            self.out_file = open(self.out_path, "a", encoding="utf-8")
        except OSError:
            self.out_file = None
        self._log(f"Started: {lo}-{hi} characters from '{cs}'"
                  + (f", starting at {start}" if start else "")
                  + (", random order" if self.v_random.get() else "")
                  + (", checking free names for locks" if self.lock_checker else ""), "info")
        self.checker.start()
        self._set_settings_state(False)
        self.btn_start.configure(state="disabled")
        self.btn_stop.configure(state="normal")
        self._status("Checking...")

    def stop(self):
        busy = False
        if self.checker and self.checker.is_running():
            self.checker.stop()
            busy = True
        if self.lock_checker and self.lock_checker.is_alive():
            self.lock_checker.stop()            # names still waiting show as "lock not checked"
            busy = True
        if busy:
            self.btn_stop.configure(state="disabled")
            self._status("Stopping...")

    def clear(self):
        self._follow_log = True
        self.txt.configure(state="normal")
        self.txt.delete("1.0", "end")
        self.txt.configure(state="disabled")
        self.lst.delete(0, "end")
        self.available_names.clear()
        self.dismiss_banner()
        if self.btn_start.instate(["!disabled"]):           # not running
            self.checker = None
            self.n_free = self.n_locked = 0
            for lbl in self.stat_labels.values():
                lbl.configure(text="0")
            self.lbl_locked_cap.configure(text="Locked")
            self.progress.configure(value=0)

    def copy_selected(self):
        names = [self.lst.get(i) for i in self.lst.curselection()]
        if names:
            self._clip("\n".join(names))
            self._status(f"Copied {len(names)} name(s)")

    def copy_all(self):
        if self.available_names:
            self._clip("\n".join(self.available_names))
            self._status(f"Copied {len(self.available_names)} name(s)")

    def _clip(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)

    def open_file(self):
        if not os.path.exists(self.out_path):
            messagebox.showinfo(APP_NAME, "No available names saved yet.")
            return
        try:
            if os.name == "nt":
                os.startfile(self.out_path)
            else:
                subprocess.Popen(["xdg-open", self.out_path])
        except Exception as e:
            messagebox.showerror(APP_NAME, str(e))

    def on_close(self):
        if self.checker:
            self.checker.stop()
        if self.lock_checker:
            self.lock_checker.stop()
        if self.out_file:
            self.out_file.close()
        try:
            self.history.close()
        except Exception:
            pass
        self.root.destroy()

    # ------------------------------------------------------------------ alerts
    def _alert_len(self):
        return max(core.MIN_LEN, min(core.MAX_LEN, self._int(self.v_alert_len, 3)))

    def _handle_alerts(self, names):
        self.alert_hits.extend(names)
        shown = ", ".join(self.alert_hits[:8]) + (" ..." if len(self.alert_hits) > 8 else "")
        n = len(self.alert_hits)
        head = "Available short name found" if n == 1 else f"{n} available short names found"
        self.banner_text.configure(text=f"{head}:  {shown}")
        self.banner.grid(row=self.banner_row, column=0, sticky="ew", padx=16, pady=(8, 0))

        if self.v_alert_notify.get():
            self.pending_toast.extend(names)
            play_alert_sound(self.root)
            flash_taskbar(self.root)
        if self.v_discord.get() and self.webhook:
            self.pending_discord.extend(names)
        if self.v_alert_stop.get() and self.checker and self.checker.is_running():
            self.stopped_by_alert = names[0]
            self.stop()

    def _flush_toast(self):
        """At most one Windows notification every 5 seconds (several finds are combined)."""
        if not self.pending_toast or time.monotonic() - self.last_toast < 5:
            return
        names = self.pending_toast
        self.pending_toast = []
        self.last_toast = time.monotonic()
        if len(names) == 1:
            title, body = f"{names[0]} is available!", "Claim it in the Minecraft launcher before someone else does."
        else:
            title = f"{len(names)} short names available!"
            body = ", ".join(names[:10]) + (" ..." if len(names) > 10 else "")
        threading.Thread(target=windows_toast, args=(title, body), daemon=True).start()

    def _flush_discord(self):
        """At most one Discord message every 5 seconds (several finds are combined)."""
        if not self.pending_discord or time.monotonic() - self.last_discord < 5:
            return
        names = self.pending_discord
        self.pending_discord = []
        self.last_discord = time.monotonic()
        verified = {n: self.alert_verified[n] for n in names if n in self.alert_verified}
        payload = notify.build_message(names, verified, everyone=self.v_everyone.get())
        url, events = self.webhook, self.events

        def work():
            ok, msg = notify.send(url, payload)
            events.put(("discord_result", ok, msg, len(names)))

        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------------ discord
    def _discord_toggled(self):
        if self.v_discord.get() and not self.webhook:
            self.open_discord()
            return
        self._save_discord_settings()

    def open_discord(self):
        if self.discord_dialog:
            self.discord_dialog.top.lift()
            return
        self.discord_dialog = DiscordDialog(self)

    def _discord_closed(self):
        self.discord_dialog = None
        if self.v_discord.get() and not self.webhook:
            self.v_discord.set(False)

    def set_discord(self, url, everyone):
        self.webhook = url or None
        self.v_everyone.set(bool(everyone))
        self.v_discord.set(bool(url))
        self._save_discord_settings()
        self._status("Discord alerts are on" if url else "Discord webhook removed")

    def _save_discord_settings(self):
        self.settings.update(discord_webhook=self.webhook or "",
                             discord_on=bool(self.v_discord.get()),
                             discord_everyone=bool(self.v_everyone.get()))
        save_settings(self.settings)

    def _only_unlocked_toggled(self):
        if self.v_alert_unlocked.get() and not self.v_lockcheck.get():
            self.v_lockcheck.set(True)
            self._lockcheck_toggled()           # opens the login window if needed

    def dismiss_banner(self):
        self.alert_hits = []
        self.banner.grid_remove()

    def copy_banner(self):
        if self.alert_hits:
            self._clip("\n".join(self.alert_hits))
            self._status(f"Copied {len(self.alert_hits)} name(s)")

    # ------------------------------------------------------------------ locked-name check
    def _start_lock_checker(self):
        self.lock_checker = core.LockChecker(
            self.token,
            on_result=lambda n, v: self.events.put(("lock", n, v)),
            on_status=lambda t: self.events.put(("status", t)),
            on_auth_failed=lambda: self.events.put(("auth_failed",)),
        )

    def _lockcheck_toggled(self):
        if self.v_lockcheck.get():
            if not self.token:
                self.open_login()
                return
            running = self.checker and self.checker.is_running()
            if running and not (self.lock_checker and self.lock_checker.accepting()):
                self._start_lock_checker()
        else:
            if self.lock_checker:
                self.lock_checker.stop()        # names still waiting show as "lock not checked"
            if self.v_alert_unlocked.get():
                self._status("'Only unlocked names' is on, so there are no alerts "
                             "while the locked check is off")

    def open_login(self):
        if self.login_dialog:
            self.login_dialog.top.lift()
            return
        self.login_dialog = LoginDialog(self)

    def _login_closed(self):
        self.login_dialog = None
        if self.v_lockcheck.get() and not self.token:
            self.v_lockcheck.set(False)
            if self.v_alert_unlocked.get():
                self.v_alert_unlocked.set(False)
                self._status("'Only unlocked names' needs the locked check - log in to use it")

    def set_login(self, token, owner):
        self.token, self.token_owner = token, owner
        if token:
            who = owner or "your account"
            self.lbl_login.configure(text=f"\u2714 Logged in as {who}", style="LoginOk.TLabel")
            self.v_lockcheck.set(True)
            self._lockcheck_toggled()
            self._status(f"Logged in as {who} - free names will be checked for locks")
        else:
            self.lbl_login.configure(text="Needs your Minecraft login", style="Hint.TLabel")
            self.v_lockcheck.set(False)
            self._lockcheck_toggled()

    def _auth_failed(self):
        if self.token is None:
            return                              # already handled
        self.token = None
        self.lbl_login.configure(text="Login ran out - log in again", style="LoginBad.TLabel")
        self.v_lockcheck.set(False)
        msg = ("Mojang stopped accepting your login (tokens run out after about a day). "
               "The locked check is off - press Log in... to get a new token.")
        if self.v_alert_unlocked.get():
            msg += " Alerts for unlocked names are paused until then."
        self._status(msg)
        self._log(msg, "info")

    # ------------------------------------------------------------------ search tab
    def focus_search(self):
        self.tabs.select(self.tab_search)
        self.e_search.focus_set()
        self.e_search.select_range(0, "end")

    def _tab_changed(self):
        if self.tabs.select() == str(self.tab_search):
            self.run_search()

    def _search_visible(self):
        return self.tabs.select() == str(self.tab_search)

    def _schedule_search(self):
        if self._search_after:
            self.root.after_cancel(self._search_after)
        self._search_after = self.root.after(250, self.run_search)

    def _search_enter(self):
        term = self.v_search.get().strip()
        if core.VALID_NAME.match(term) and self.history.get(term) is None:
            self.check_now()
        else:
            self.run_search()

    def run_search(self):
        self._search_after = None
        self._search_id += 1
        sid = self._search_id
        term = self.v_search.get().strip().lower()
        statuses = {"all": None, "free": (history.FREE,), "locked": history.LOCKED,
                    "taken": (history.TAKEN,)}[self.v_filter.get()]
        self._search_busy = True
        self._last_search = time.monotonic()

        def work():
            try:
                h = history.History(self.db_path)       # own connection for this thread
                try:
                    rows = h.search(term, statuses, SEARCH_LIMIT)
                    count = h.count(term, statuses)
                    exact = h.get(term) if term else None
                    totals = h.totals()
                finally:
                    h.close()
                self.events.put(("search", sid, term, rows, count, exact, totals))
            except Exception as e:
                self.events.put(("search_error", sid, str(e)))

        threading.Thread(target=work, daemon=True).start()

    def _show_search(self, sid, term, rows, count, exact, totals):
        if sid != self._search_id:
            return                                      # an older search - ignore
        self._search_busy = False
        self.tree.delete(*self.tree.get_children())
        for name, st, checked in rows:
            self.tree.insert("", "end", values=(name, STATUS_TEXT.get(st, "?"), time_ago(checked)),
                             tags=(STATUS_TAG.get(st, "taken"),))
        more = f" (showing first {SEARCH_LIMIT:,})" if count > SEARCH_LIMIT else ""
        plus = "+" if count >= history.COUNT_CAP else ""
        self.lbl_matches.configure(
            text=f"{fmt_count(count)}{plus} match{'es' if count != 1 else ''}{more}")
        self.lbl_totals.configure(text=f"{totals[0]:,} names checked so far  -  "
                                       f"{totals[1]:,} available  -  {totals[2]:,} locked")

        valid = bool(core.VALID_NAME.match(term))
        if exact:
            st, checked = exact
            style = {history.FREE: "Free.TLabel", history.TAKEN: "Taken.TLabel"}.get(st, "Locked.TLabel")
            word = STATUS_TEXT.get(st, "?").upper().replace(" - ", " (") + (")" if st in history.LOCKED else "")
            self.lbl_lookup.configure(text=f"{term}  is  {word}   (checked {time_ago(checked)})",
                                      style=style)
        elif valid:
            self.lbl_lookup.configure(text=f"{term}  hasn't been checked yet.", style="TLabel")
        elif term:
            self.lbl_lookup.configure(text="Showing every checked name that contains your text.",
                                      style="Hint.TLabel")
        else:
            self.lbl_lookup.configure(text="Type part of a name, e.g.  ab  finds abc, xab9 ...",
                                      style="Hint.TLabel")
        if valid:
            self.btn_check_now.configure(text="Check again" if exact else "Check now",
                                         state="normal")
            self.btn_check_now.pack(side="left", padx=(10, 0))
        else:
            self.btn_check_now.pack_forget()

    def check_now(self):
        name = self.v_search.get().strip()
        if not core.VALID_NAME.match(name):
            return
        self.btn_check_now.configure(state="disabled")
        self.lbl_lookup.configure(text=f"Checking {name} ...", style="TLabel")

        token = self.token

        def work():
            try:
                if not core.check_one(name):
                    verdict = None                          # taken
                elif token:
                    try:
                        verdict = core.check_claimable(name, token)
                    except core.AuthFailed:
                        self.events.put(("auth_failed",))
                        verdict = core.UNVERIFIED
                    except (core.RateLimited, core.LookupFailed):
                        verdict = core.UNVERIFIED
                else:
                    verdict = core.UNVERIFIED
                self.events.put(("checked_one", name, verdict, None))
            except Exception as e:
                self.events.put(("checked_one", name, None, str(e)))

        threading.Thread(target=work, daemon=True).start()

    def _checked_one(self, name, verdict, error):
        if error:
            self.lbl_lookup.configure(text=f"Couldn't check {name}: {error}", style="TLabel")
            self.btn_check_now.configure(state="normal")
            return
        st = history.TAKEN if verdict is None else VERDICT_STATUS[verdict]
        self.history.add_many([(name, st)])
        if st == history.FREE:
            self._add_available([name.lower()])
        msg = {history.TAKEN: "taken", history.FREE: "available"}.get(st, "locked")
        if verdict == core.UNVERIFIED:
            msg += " (lock not checked - log in under Locked names to check)"
        elif st in history.LOCKED:
            msg += f" - {LOCK_REASON[verdict]}"
        self._status(f"{name} is {msg}")
        self.run_search()

    def copy_tree_selection(self):
        names = [self.tree.item(i, "values")[0] for i in self.tree.selection()]
        if names:
            self._clip("\n".join(names))
            self._status(f"Copied {len(names)} name(s)")

    def forget_history(self):
        if not messagebox.askyesno(APP_NAME, "Forget every checked name? "
                                             "(available.txt is not touched)"):
            return
        self.history.clear()
        self.run_search()

    # ------------------------------------------------------------------ updates
    def _status(self, text):
        self.lbl_status.configure(text=text)

    def _log(self, line, tag):
        self.txt.configure(state="normal")
        self.txt.insert("end", line + "\n", tag)
        self.txt.configure(state="disabled")
        if self._follow_log:
            self.txt.see("end")

    def _log_scrolled(self):
        self.root.after(50, lambda: setattr(self, "_follow_log", self.txt.yview()[1] > 0.995))

    def _add_available(self, names, alert=()):
        start = self.lst.size()
        self.available_names.extend(names)
        self.lst.insert("end", *names)
        for i, n in enumerate(names):
            if n in alert:
                self.lst.itemconfigure(start + i, background=WIN_SUCCESS_BG)
        self.lst.see("end")
        try:
            if self.out_file:
                self.out_file.write("\n".join(names) + "\n")
                self.out_file.flush()
            else:
                with open(self.out_path, "a", encoding="utf-8") as f:
                    f.write("\n".join(names) + "\n")
        except OSError:
            pass

    def _poll(self):
        lines, new_free, results, alerts = [], [], [], []
        status = None
        show_taken = self.v_show_taken.get()
        lc = self.lock_checker
        alert_on = self.v_alert_on.get()
        alert_n = self._alert_len()
        alert_exact = self.v_alert_mode.get() == "exactly"
        only_unlocked = self.v_alert_unlocked.get()

        def wants_alert(name, lock):
            if not alert_on:
                return False
            if (len(name) != alert_n) if alert_exact else (len(name) > alert_n):
                return False
            return lock is True or not only_unlocked

        def show_free(name, lock=None):
            """lock: None = no locked check this run, True = checked & free, False = not checked."""
            note = {None: "", True: "  - not locked", False: "  - lock not checked"}[lock]
            new_free.append(name)
            self.n_free += 1
            if wants_alert(name, lock):
                alerts.append(name)
                if lock is not None:
                    self.alert_verified[name] = lock
                lines.append((f"{name:<17}AVAILABLE{note}   \u2605 alert!", "alert"))
            else:
                lines.append((f"{name:<17}AVAILABLE{note}", "free"))

        try:
            for _ in range(20000):
                ev = self.events.get_nowait()
                kind = ev[0]
                if kind == "result":
                    _, name, free = ev
                    results.append((name, history.FREE if free else history.TAKEN))
                    if free:
                        if lc and lc.accepting():
                            lc.submit(name)                 # shown once the locked check answers
                        else:
                            show_free(name, False if lc else None)
                    elif show_taken:
                        lines.append((f"{name:<17}taken", "taken"))
                elif kind == "lock":
                    _, name, verdict = ev
                    if verdict in (core.BLOCKED, core.HELD):
                        self.n_locked += 1
                        results.append((name, VERDICT_STATUS[verdict]))
                        lines.append((f"{name:<17}LOCKED  - {LOCK_REASON[verdict]}", "locked"))
                    elif verdict == core.CLAIMABLE:
                        show_free(name, True)
                    else:
                        show_free(name, False)
                elif kind == "auth_failed":
                    self._auth_failed()
                elif kind == "login_result":
                    if self.login_dialog:
                        self.login_dialog.tested(*ev[1:])
                elif kind == "discord_result":
                    _, ok, msg, n = ev
                    status = (f"Sent {n} name(s) to Discord" if ok else f"Discord: {msg}")
                elif kind == "discord_test":
                    if self.discord_dialog:
                        self.discord_dialog.tested(*ev[1:])
                elif kind == "status":
                    status = ev[1]
                elif kind == "search":
                    self._show_search(*ev[1:])
                elif kind == "search_error":
                    if ev[1] == self._search_id:
                        self._search_busy = False
                        self.lbl_matches.configure(text=f"Search failed: {ev[2]}")
                elif kind == "checked_one":
                    self._checked_one(*ev[1:])
        except queue.Empty:
            pass

        if results:
            try:
                self.history.add_many(results)
            except Exception as e:
                status = f"Couldn't save to checked_names.db: {e}"

        if lines:
            self.txt.configure(state="normal")
            chunk = []
            for text, tag in lines[-MAX_LOG_LINES:]:
                chunk.extend((text + "\n", tag))
            self.txt.insert("end", *chunk)
            extra = int(self.txt.index("end-1c").split(".")[0]) - MAX_LOG_LINES
            if extra > 0:
                self.txt.delete("1.0", f"{extra + 1}.0")
            self.txt.configure(state="disabled")
            if self._follow_log:
                self.txt.see("end")

        if new_free:
            self._add_available(new_free, alert=set(alerts))
        if alerts:
            self._handle_alerts(alerts)
        self._flush_toast()
        self._flush_discord()

        c = self.checker
        if c:
            running = c.is_running()
            lc = self.lock_checker
            waiting = lc.pending() if lc and lc.is_alive() else 0
            self.stat_labels["checked"].configure(text=fmt_count(c.checked))
            self.stat_labels["available"].configure(text=fmt_count(self.n_free))
            self.stat_labels["locked"].configure(text=fmt_count(self.n_locked))
            self.stat_labels["taken"].configure(text=fmt_count(c.taken))
            self.stat_labels["speed"].configure(text=f"{c.speed():.0f}" if running else "0")
            self.lbl_locked_cap.configure(text=f"Locked ({waiting} to check)" if waiting else "Locked")
            if self.total:
                done = (self.skipped + c.checked) / self.total
                self.progress.configure(value=min(1000, done * 1000))
            if not running and lc and lc.is_alive():
                if waiting == 0 and self.events.empty():
                    lc.stop()                               # every free name has been lock-checked
                elif not status and lc.accepting():
                    status = f"Checking {waiting} free name(s) for locks..."
            lock_busy = bool(lc and lc.is_alive())
            if (not running and not lock_busy and self.btn_start.instate(["disabled"])
                    and self.events.empty()):
                self._finished()
            # keep the search tab fresh while checking
            if (running and self._search_visible() and not self._search_busy
                    and time.monotonic() - self._last_search > 3):
                self.run_search()

        if status:
            self._status(status)
        self.root.after(100, self._poll)

    def _finished(self):
        c = self.checker
        counts = f"{self.n_free:,} available" + (f", {self.n_locked:,} locked" if self.n_locked else "")
        if c.finished_all():
            self._status(f"Done - checked every name. {counts}.")
            self._log("Finished: every name checked.", "info")
            self.v_start.set("")
        else:
            resume = None if self.v_random.get() else c.resume_point()
            if resume:
                self.v_start.set(resume)
            why = f"Found {self.stopped_by_alert}! " if self.stopped_by_alert else ""
            self._status(f"{why}Stopped. Checked {c.checked:,}, {counts}."
                         + (f"  Press Start to continue from {resume}." if resume else ""))
            self._log("Stopped." + (f" Will continue from {resume}." if resume else ""), "info")
        if self.out_file:
            self.out_file.close()
            self.out_file = None
        self._set_settings_state(True)
        self.btn_start.configure(state="normal")
        self.btn_stop.configure(state="disabled")
        if self.selftest:
            self.root.after(1500, self._selftest_report)

    # ------------------------------------------------------------------ CI self-test
    def _screenshot(self, filename):
        import importlib
        grab = importlib.import_module("PIL.ImageGrab")   # only present in CI
        self.root.update()
        x, y = self.root.winfo_rootx(), self.root.winfo_rooty()
        w, h = self.root.winfo_width(), self.root.winfo_height()
        grab.grab(bbox=(x, y, x + w, y + h)).save(os.path.join(app_dir(), filename))

    def _selftest_report(self):
        c = self.checker
        report = os.path.join(app_dir(), "selftest.txt")
        with open(report, "w", encoding="utf-8") as f:
            f.write(f"mode={self.selftest}\n")
            f.write(f"checked={c.checked} available={c.available} taken={c.taken}\n")
            f.write(f"stopped_by_alert={self.stopped_by_alert}\n")
            f.write(f"available_names={self.available_names[:30]}\n")
            f.write(f"db_totals={self.history.totals()}\n")
            f.write(f"status={self.lbl_status.cget('text')}\n")
        try:
            self._screenshot("screenshot.png")
        except Exception as e:
            with open(report, "a", encoding="utf-8") as f:
                f.write(f"screenshot failed: {e}\n")
        # then show the search tab with a query and a never-checked name
        self.tabs.select(self.tab_search)
        self.v_search.set("qzb")
        self.root.after(2500, self._selftest_search_shot)

    def _selftest_search_shot(self):
        with open(os.path.join(app_dir(), "selftest.txt"), "a", encoding="utf-8") as f:
            f.write(f"search_matches={self.lbl_matches.cget('text')} "
                    f"rows={len(self.tree.get_children())}\n")
        try:
            self._screenshot("screenshot_search.png")
        except Exception:
            pass
        self.root.after(300, self.on_close)


class LoginDialog:
    """Asks for the Minecraft access token used by the locked-name check."""

    STEPS = (
        "In Chrome or Edge, go to minecraft.net and log in.",
        "Press F12 and open the Application tab.",
        "On the left, open Cookies and click https://www.minecraft.net.",
        "Click the cookie named  bearer_token  and copy its Value.",
        "Paste it below and press Test and use.",
    )

    def __init__(self, app):
        self.app = app
        top = self.top = tk.Toplevel(app.root)
        top.title("Log in - locked-name check")
        top.configure(bg=WIN_BG)
        top.transient(app.root)
        top.resizable(False, False)
        try:
            top.iconbitmap(resource("icon.ico"))
        except Exception:
            pass
        wrap = 480
        frm = ttk.Frame(top, padding=18)
        frm.pack(fill="both", expand=True)
        ttk.Label(frm, text="Log in to spot locked names",
                  font=(app.font_family, 13)).pack(anchor="w")
        ttk.Label(frm, wraplength=wrap, justify="left", text=(
            "A name nobody owns can still be locked: blocked by Mojang, or on hold for a while "
            "after someone changed away from it. Only Mojang's logged-in check can tell (it's the "
            "same check the launcher does when you change your name), so it needs your Minecraft "
            "access token.")).pack(anchor="w", pady=(6, 10))

        steps = ttk.LabelFrame(frm, text=" How to get your token ", padding=(12, 6))
        steps.pack(fill="x")
        for i, step in enumerate(self.STEPS, 1):
            ttk.Label(steps, text=f"{i}.  {step}", wraplength=wrap - 30, justify="left"
                      ).pack(anchor="w", pady=1)

        row = ttk.Frame(frm)
        row.pack(fill="x", pady=(12, 2))
        ttk.Label(row, text="Token:").pack(side="left")
        self.v_token = tk.StringVar(value=app.token or "")
        self.entry = ttk.Entry(row, textvariable=self.v_token, show="\u2022", width=46)
        self.entry.pack(side="left", padx=6, fill="x", expand=True)
        ttk.Button(row, text="Paste", command=self.paste, padding=(6, 1)).pack(side="left")
        self.v_show = tk.BooleanVar(value=False)
        ttk.Checkbutton(frm, text="Show token", variable=self.v_show,
                        command=lambda: self.entry.configure(
                            show="" if self.v_show.get() else "\u2022")).pack(anchor="w")
        self.lbl = ttk.Label(frm, text="", wraplength=wrap, justify="left")
        self.lbl.pack(anchor="w", pady=(6, 0))
        ttk.Label(frm, style="Hint.TLabel", wraplength=wrap, justify="left", text=(
            "Keep this token private: anyone who has it can use your Minecraft account until it "
            "runs out (about 24 hours). This program only sends it to Mojang and never saves it.")
                  ).pack(anchor="w", pady=(8, 12))

        btns = ttk.Frame(frm)
        btns.pack(fill="x")
        ttk.Button(btns, text="Close", command=self.close).pack(side="right")
        if app.token:
            ttk.Button(btns, text="Log out", command=self.logout).pack(side="right", padx=(0, 6))
        self.btn_ok = ttk.Button(btns, text="Test and use", style="Accent.TButton",
                                 command=self.test)
        self.btn_ok.pack(side="right", padx=(0, 6))

        top.bind("<Return>", lambda e: self.test())
        top.bind("<Escape>", lambda e: self.close())
        top.protocol("WM_DELETE_WINDOW", self.close)
        top.update_idletasks()
        x = app.root.winfo_rootx() + (app.root.winfo_width() - top.winfo_reqwidth()) // 2
        y = app.root.winfo_rooty() + 40
        top.geometry(f"+{max(0, x)}+{max(0, y)}")
        top.grab_set()
        self.entry.focus_set()

    def paste(self):
        try:
            self.v_token.set(core.clean_token(self.app.root.clipboard_get()))
        except tk.TclError:
            pass

    def test(self):
        token = core.clean_token(self.v_token.get())
        if not token:
            self.lbl.configure(text="Paste your token first.", style="LoginBad.TLabel")
            return
        self.btn_ok.configure(state="disabled")
        self.lbl.configure(text="Checking the token with Mojang ...", style="TLabel")
        events = self.app.events

        def work():
            try:
                res = ("ok", core.token_owner(token))
            except core.AuthFailed:
                res = ("bad", "Mojang didn't accept this token - it's wrong or has run out. "
                              "Get a fresh one and try again.")
            except core.RateLimited:
                res = ("bad", "Mojang is busy right now - try again in a minute.")
            except Exception as e:
                res = ("bad", f"Couldn't reach Mojang: {e}")
            events.put(("login_result", token, res))

        threading.Thread(target=work, daemon=True).start()

    def tested(self, token, res):
        kind, value = res
        if kind == "ok":
            self.app.set_login(token, value)
            self.close()
        else:
            self.btn_ok.configure(state="normal")
            self.lbl.configure(text=value, style="LoginBad.TLabel")

    def logout(self):
        self.app.set_login(None, None)
        self.close()

    def close(self):
        try:
            self.top.grab_release()
            self.top.destroy()
        except tk.TclError:
            pass
        self.app._login_closed()


class DiscordDialog:
    """Asks for the Discord webhook URL that alerts are posted to."""

    STEPS = (
        "In Discord, open your server's Server Settings, then Integrations, then Webhooks.",
        "Click New Webhook and pick the channel the alerts should go to.",
        "Click Copy Webhook URL and paste it below.",
    )

    def __init__(self, app):
        self.app = app
        top = self.top = tk.Toplevel(app.root)
        top.title("Discord alerts")
        top.configure(bg=WIN_BG)
        top.transient(app.root)
        top.resizable(False, False)
        try:
            top.iconbitmap(resource("icon.ico"))
        except Exception:
            pass
        wrap = 480
        frm = ttk.Frame(top, padding=18)
        frm.pack(fill="both", expand=True)
        ttk.Label(frm, text="Send alerts to Discord", font=(app.font_family, 13)).pack(anchor="w")
        ttk.Label(frm, wraplength=wrap, justify="left", text=(
            "The checker posts a message in a Discord channel for every name you'd get an alert "
            "for - the same names as the Windows notification, using the alert settings in the "
            "main window.")).pack(anchor="w", pady=(6, 10))

        steps = ttk.LabelFrame(frm, text=" How to get a webhook URL ", padding=(12, 6))
        steps.pack(fill="x")
        for i, step in enumerate(self.STEPS, 1):
            ttk.Label(steps, text=f"{i}.  {step}", wraplength=wrap - 30, justify="left"
                      ).pack(anchor="w", pady=1)

        row = ttk.Frame(frm)
        row.pack(fill="x", pady=(12, 2))
        ttk.Label(row, text="Webhook URL:").pack(side="left")
        self.v_url = tk.StringVar(value=app.webhook or "")
        ttk.Entry(row, textvariable=self.v_url, width=44).pack(side="left", padx=6, fill="x",
                                                               expand=True)
        ttk.Button(row, text="Paste", command=self.paste, padding=(6, 1)).pack(side="left")
        self.v_everyone = tk.BooleanVar(value=app.v_everyone.get())
        ttk.Checkbutton(frm, text="Mention @everyone in alerts", variable=self.v_everyone
                        ).pack(anchor="w", pady=(4, 0))
        self.lbl = ttk.Label(frm, text="", wraplength=wrap, justify="left")
        self.lbl.pack(anchor="w", pady=(6, 0))
        ttk.Label(frm, style="Hint.TLabel", wraplength=wrap, justify="left", text=(
            "Anyone who has this URL can post in that channel, so keep it private. It's saved in "
            "settings.json next to this program so you only paste it once.")
                  ).pack(anchor="w", pady=(8, 12))

        btns = ttk.Frame(frm)
        btns.pack(fill="x")
        self.btn_test = ttk.Button(btns, text="Send test message", command=self.test)
        self.btn_test.pack(side="left")
        ttk.Button(btns, text="Close", command=self.close).pack(side="right")
        if app.webhook:
            ttk.Button(btns, text="Remove", command=self.remove).pack(side="right", padx=(0, 6))
        ttk.Button(btns, text="Save", style="Accent.TButton", command=self.save
                   ).pack(side="right", padx=(0, 6))

        top.bind("<Return>", lambda e: self.save())
        top.bind("<Escape>", lambda e: self.close())
        top.protocol("WM_DELETE_WINDOW", self.close)
        top.update_idletasks()
        x = app.root.winfo_rootx() + (app.root.winfo_width() - top.winfo_reqwidth()) // 2
        y = app.root.winfo_rooty() + 40
        top.geometry(f"+{max(0, x)}+{max(0, y)}")
        top.grab_set()

    def _url(self):
        url = self.v_url.get().strip()
        if not notify.is_webhook_url(url):
            self.lbl.configure(style="LoginBad.TLabel", text=(
                "That doesn't look like a Discord webhook URL. It should start with "
                "https://discord.com/api/webhooks/"))
            return None
        return url

    def paste(self):
        try:
            self.v_url.set(self.app.root.clipboard_get().strip())
        except tk.TclError:
            pass

    def test(self):
        url = self._url()
        if not url:
            return
        self.btn_test.configure(state="disabled")
        self.lbl.configure(text="Sending a test message ...", style="TLabel")
        payload = notify.build_message([], test=True, everyone=self.v_everyone.get())
        events = self.app.events

        def work():
            ok, msg = notify.send(url, payload)
            events.put(("discord_test", ok, msg))

        threading.Thread(target=work, daemon=True).start()

    def tested(self, ok, msg):
        self.btn_test.configure(state="normal")
        if ok:
            self.lbl.configure(text="\u2714 Test message sent - check your Discord channel.",
                               style="LoginOk.TLabel")
        else:
            self.lbl.configure(text=msg, style="LoginBad.TLabel")

    def save(self):
        url = self._url()
        if url:
            self.app.set_discord(url, self.v_everyone.get())
            self.close()

    def remove(self):
        self.app.set_discord(None, False)
        self.close()

    def close(self):
        try:
            self.top.grab_release()
            self.top.destroy()
        except tk.TclError:
            pass
        self.app._discord_closed()


def main():
    selftest = None
    if "--selftest" in sys.argv:
        selftest = "run"
    if "--selftest-alert" in sys.argv:
        selftest = "alert"
    enable_dpi_awareness()
    root = tk.Tk()
    # fit small screens (e.g. 1366x768 laptops): leave room for the taskbar
    height = max(600, min(760, root.winfo_screenheight() - 90))
    root.geometry(f"960x{height}+{max(0, (root.winfo_screenwidth() - 960) // 2)}+10")
    root.minsize(820, min(640, height))
    app = App(root, selftest=selftest)
    if selftest:
        app.v_letters.set(True)
        app.v_digits.set(False)
        app.v_min.set(4)
        app.v_max.set(4)
        app.v_start.set("qzaa")
        app.v_alert_len.set(4)                 # 4-letter names trigger the alert in tests
        app.v_alert_stop.set(selftest == "alert")
        root.after(800, app.start)
        root.after(20000, app.stop)
    root.mainloop()


if __name__ == "__main__":
    main()
