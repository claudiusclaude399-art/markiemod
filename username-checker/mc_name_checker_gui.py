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

import checker_core as core
import history

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
WIN_HIGHLIGHT = "#FFF4CE"       # Windows "warning" yellow - used to mark short-name hits

MAX_LOG_LINES = 3000
SEARCH_LIMIT = 500


def app_dir():
    """Folder of the .exe (or of this script) - where our files go."""
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


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
        ttk.Label(head, text=APP_NAME, style="Title.TLabel").pack(anchor="w")
        ttk.Label(head, text="Finds unclaimed Minecraft names.  Green = available,  red = taken.",
                  style="Sub.TLabel").pack(anchor="w")

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
        s.grid(row=0, column=0, rowspan=2, sticky="nsew", padx=(0, 8))

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
        ttk.Label(o, text="(self-tuning)", style="Hint.TLabel").grid(row=0, column=2, sticky="w",
                                                                    padx=(6, 0))
        ttk.Checkbutton(o, text="Show taken names (red)", variable=self.v_show_taken
                        ).grid(row=1, column=0, columnspan=3, sticky="w", pady=(2, 0))

        # --- short-name alerts
        a = ttk.LabelFrame(top, text=" Short-name alerts ", padding=(12, 4))
        a.grid(row=1, column=1, sticky="nsew", pady=(6, 0))
        self.v_alert_on = tk.BooleanVar(value=True)
        self.v_alert_len = tk.IntVar(value=3)
        self.v_alert_notify = tk.BooleanVar(value=True)
        self.v_alert_stop = tk.BooleanVar(value=False)
        af = ttk.Frame(a)
        af.grid(row=0, column=0, sticky="w")
        ttk.Checkbutton(af, text="Alert for free names up to", variable=self.v_alert_on
                        ).pack(side="left")
        self.sp_alert = ttk.Spinbox(af, from_=core.MIN_LEN, to=core.MAX_LEN, width=3,
                                    textvariable=self.v_alert_len)
        self.sp_alert.pack(side="left", padx=4)
        ttk.Label(af, text="letters").pack(side="left")
        ttk.Checkbutton(a, text="Windows notification + sound", variable=self.v_alert_notify
                        ).grid(row=1, column=0, sticky="w", padx=(20, 0), pady=1)
        ttk.Checkbutton(a, text="Stop searching when one is found", variable=self.v_alert_stop
                        ).grid(row=2, column=0, sticky="w", padx=(20, 0), pady=1)

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
                             ("taken", "Taken", "Red.StatNum.TLabel"),
                             ("speed", "Names / sec", "Blue.StatNum.TLabel")):
            f = ttk.Frame(stats)
            f.pack(side="left", padx=(18, 0))
            lbl = ttk.Label(f, text="0", style=st)
            lbl.pack(anchor="e")
            ttk.Label(f, text=cap, style="StatCap.TLabel").pack(anchor="e")
            self.stat_labels[key] = lbl

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
        sb = ttk.Scrollbar(lf, orient="vertical", command=self.txt.yview)
        self.txt.configure(yscrollcommand=sb.set)
        self.txt.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.txt.tag_configure("free", foreground=WIN_GREEN, font=(self.font_mono[0], 10, "bold"))
        self.txt.tag_configure("alert", foreground=WIN_GREEN, background=WIN_HIGHLIGHT,
                               font=(self.font_mono[0], 10, "bold"))
        self.txt.tag_configure("taken", foreground=WIN_RED)
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
        for text, val in (("All", "all"), ("Available", "free"), ("Taken", "taken")):
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
                  + (", random order" if self.v_random.get() else ""), "info")
        self.checker.start()
        self._set_settings_state(False)
        self.btn_start.configure(state="disabled")
        self.btn_stop.configure(state="normal")
        self._status("Checking...")

    def stop(self):
        if self.checker and self.checker.is_running():
            self.checker.stop()
            self.btn_stop.configure(state="disabled")
            self._status("Stopping...")

    def clear(self):
        self.txt.configure(state="normal")
        self.txt.delete("1.0", "end")
        self.txt.configure(state="disabled")
        self.lst.delete(0, "end")
        self.available_names.clear()
        self.dismiss_banner()
        if not (self.checker and self.checker.is_running()):
            for lbl in self.stat_labels.values():
                lbl.configure(text="0")
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

    def dismiss_banner(self):
        self.alert_hits = []
        self.banner.grid_remove()

    def copy_banner(self):
        if self.alert_hits:
            self._clip("\n".join(self.alert_hits))
            self._status(f"Copied {len(self.alert_hits)} name(s)")

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
        available = {"all": None, "free": True, "taken": False}[self.v_filter.get()]
        self._search_busy = True
        self._last_search = time.monotonic()

        def work():
            try:
                h = history.History(self.db_path)       # own connection for this thread
                try:
                    rows = h.search(term, available, SEARCH_LIMIT)
                    count = h.count(term, available)
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
        for name, avail, checked in rows:
            self.tree.insert("", "end", values=(name, "AVAILABLE" if avail else "taken",
                                                time_ago(checked)),
                             tags=("free" if avail else "taken",))
        more = f" (showing first {SEARCH_LIMIT:,})" if count > SEARCH_LIMIT else ""
        plus = "+" if count >= history.COUNT_CAP else ""
        self.lbl_matches.configure(
            text=f"{fmt_count(count)}{plus} match{'es' if count != 1 else ''}{more}")
        self.lbl_totals.configure(text=f"{totals[0]:,} names checked so far  -  "
                                       f"{totals[1]:,} available")

        valid = bool(core.VALID_NAME.match(term))
        if exact:
            avail, checked = exact
            self.lbl_lookup.configure(
                text=f"{term}  is  {'AVAILABLE' if avail else 'TAKEN'}   (checked {time_ago(checked)})",
                style="Free.TLabel" if avail else "Taken.TLabel")
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

        def work():
            try:
                self.events.put(("checked_one", name, core.check_one(name), None))
            except Exception as e:
                self.events.put(("checked_one", name, None, str(e)))

        threading.Thread(target=work, daemon=True).start()

    def _checked_one(self, name, available, error):
        if error:
            self.lbl_lookup.configure(text=f"Couldn't check {name}: {error}", style="TLabel")
            self.btn_check_now.configure(state="normal")
            return
        self.history.add_many([(name, available)])
        if available:
            self._add_available([name.lower()])
        self._status(f"{name} is {'available' if available else 'taken'}")
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

    def _add_available(self, names, alert=()):
        start = self.lst.size()
        self.available_names.extend(names)
        self.lst.insert("end", *names)
        for i, n in enumerate(names):
            if n in alert:
                self.lst.itemconfigure(start + i, background=WIN_HIGHLIGHT)
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
        alert_len = self._alert_len() if self.v_alert_on.get() else 0
        try:
            for _ in range(20000):
                ev = self.events.get_nowait()
                kind = ev[0]
                if kind == "result":
                    _, name, free = ev
                    results.append((name, free))
                    if free:
                        new_free.append(name)
                        if len(name) <= alert_len:
                            alerts.append(name)
                            lines.append((f"{name:<17}AVAILABLE   ★ short name!", "alert"))
                        else:
                            lines.append((f"{name:<17}AVAILABLE", "free"))
                    elif show_taken:
                        lines.append((f"{name:<17}taken", "taken"))
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
            at_bottom = self.txt.yview()[1] > 0.999
            self.txt.configure(state="normal")
            chunk = []
            for text, tag in lines[-MAX_LOG_LINES:]:
                chunk.extend((text + "\n", tag))
            self.txt.insert("end", *chunk)
            extra = int(self.txt.index("end-1c").split(".")[0]) - MAX_LOG_LINES
            if extra > 0:
                self.txt.delete("1.0", f"{extra + 1}.0")
            self.txt.configure(state="disabled")
            if at_bottom:
                self.txt.see("end")

        if new_free:
            self._add_available(new_free, alert=set(alerts))
        if alerts:
            self._handle_alerts(alerts)
        self._flush_toast()

        if status:
            self._status(status)

        c = self.checker
        if c:
            running = c.is_running()
            self.stat_labels["checked"].configure(text=fmt_count(c.checked))
            self.stat_labels["available"].configure(text=fmt_count(c.available))
            self.stat_labels["taken"].configure(text=fmt_count(c.taken))
            self.stat_labels["speed"].configure(text=f"{c.speed():.0f}" if running else "0")
            if self.total:
                done = (self.skipped + c.checked) / self.total
                self.progress.configure(value=min(1000, done * 1000))
            if not running and self.btn_start.instate(["disabled"]) and self.events.empty():
                self._finished()
            # keep the search tab fresh while checking
            if (running and self._search_visible() and not self._search_busy
                    and time.monotonic() - self._last_search > 3):
                self.run_search()

        self.root.after(100, self._poll)

    def _finished(self):
        c = self.checker
        if c.finished_all():
            self._status(f"Done - checked every name. {c.available:,} available.")
            self._log("Finished: every name checked.", "info")
            self.v_start.set("")
        else:
            resume = None if self.v_random.get() else c.resume_point()
            if resume:
                self.v_start.set(resume)
            why = f"Found {self.stopped_by_alert}! " if self.stopped_by_alert else ""
            self._status(f"{why}Stopped. Checked {c.checked:,}, {c.available:,} available."
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
