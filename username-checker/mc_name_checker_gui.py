"""
Minecraft Username Checker - Windows 10 style GUI.

Green = available, red = taken. Available names are saved to available.txt
next to the program.
"""

import os
import queue
import sys
import time
import tkinter as tk
from tkinter import messagebox, ttk
import tkinter.font as tkfont

import checker_core as core

APP_NAME = "Minecraft Username Checker"

# Windows 10 colours
WIN_GREEN = "#107C10"
WIN_RED = "#E81123"
WIN_BLUE = "#0078D7"
WIN_GREY = "#767676"
WIN_BG = "#FFFFFF"
WIN_PANEL = "#F2F2F2"

MAX_LOG_LINES = 3000


def app_dir():
    """Folder of the .exe (or of this script) - where available.txt goes."""
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


class App:
    def __init__(self, root, selftest=False):
        self.root = root
        self.selftest = selftest
        self.checker = None
        self.events = queue.Queue()
        self.available_names = []
        self.out_path = os.path.join(app_dir(), "available.txt")
        self.out_file = None

        self._setup_style()
        self._build()
        self._update_estimate()
        self.root.after(100, self._poll)

    # ------------------------------------------------------------------ look
    def _setup_style(self):
        root = self.root
        root.title(APP_NAME)
        root.configure(bg=WIN_BG)
        root.minsize(760, 560)
        try:
            root.iconbitmap(resource("icon.ico"))
        except Exception:
            pass

        self.style = style = ttk.Style(root)
        for theme in ("vista", "winnative", "clam"):
            if theme in style.theme_names():
                style.theme_use(theme)
                break

        family = "Segoe UI" if "Segoe UI" in tkfont.families(root) else "TkDefaultFont"
        mono = ("Consolas" if "Consolas" in tkfont.families(root)
                else tkfont.nametofont("TkFixedFont").actual("family"))
        for fname in ("TkDefaultFont", "TkTextFont", "TkMenuFont", "TkHeadingFont"):
            try:
                tkfont.nametofont(fname).configure(family=family, size=9)
            except tk.TclError:
                pass
        self.font_title = (family if family != "TkDefaultFont" else None, 16)
        self.font_sub = (family if family != "TkDefaultFont" else None, 9)
        self.font_big = (family if family != "TkDefaultFont" else None, 14)
        self.font_mono = (mono, 10)

        style.configure("TFrame", background=WIN_BG)
        style.configure("TLabel", background=WIN_BG)
        style.configure("TCheckbutton", background=WIN_BG)
        style.configure("TRadiobutton", background=WIN_BG)
        style.configure("TLabelframe", background=WIN_BG)
        style.configure("TLabelframe.Label", background=WIN_BG, foreground=WIN_BLUE)
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
        style.configure("Accent.TButton", padding=(14, 4))
        style.configure("TButton", padding=(10, 3))

    # ------------------------------------------------------------------ layout
    def _build(self):
        root = self.root
        root.columnconfigure(0, weight=1)
        root.rowconfigure(3, weight=1)

        # header
        head = ttk.Frame(root, padding=(16, 12, 16, 4))
        head.grid(row=0, column=0, sticky="ew")
        ttk.Label(head, text=APP_NAME, style="Title.TLabel").pack(anchor="w")
        ttk.Label(head, text="Finds unclaimed Minecraft names.  Green = available,  red = taken.",
                  style="Sub.TLabel").pack(anchor="w")

        # settings
        top = ttk.Frame(root, padding=(16, 6, 16, 0))
        top.grid(row=1, column=0, sticky="ew")
        top.columnconfigure(0, weight=3)
        top.columnconfigure(1, weight=2)

        s = ttk.LabelFrame(top, text=" Search ", padding=(12, 6))
        s.grid(row=0, column=0, sticky="nsew", padx=(0, 8))

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
        ttk.Label(sf, text="  optional - filled in automatically when you stop",
                  style="Hint.TLabel").pack(side="left")

        self.lbl_estimate = ttk.Label(s, text="", style="Hint.TLabel")
        self.lbl_estimate.grid(row=4, column=0, columnspan=2, sticky="w", pady=(4, 0))

        # speed + options
        o = ttk.LabelFrame(top, text=" Speed ", padding=(12, 6))
        o.grid(row=0, column=1, sticky="nsew")
        self.v_threads = tk.IntVar(value=12)
        self.v_show_taken = tk.BooleanVar(value=True)
        ttk.Label(o, text="Parallel requests:").grid(row=0, column=0, sticky="w", pady=3)
        self.sp_threads = ttk.Spinbox(o, from_=1, to=64, width=5, textvariable=self.v_threads)
        self.sp_threads.grid(row=0, column=1, sticky="w", padx=(6, 0))
        ttk.Label(o, text="More = faster. The speed tunes itself\nto stay under Mojang's limits.",
                  style="Hint.TLabel", justify="left").grid(row=1, column=0, columnspan=2,
                                                            sticky="w", pady=(0, 6))
        ttk.Checkbutton(o, text="Show taken names (red)", variable=self.v_show_taken
                        ).grid(row=2, column=0, columnspan=2, sticky="w")

        # buttons + stats
        bar = ttk.Frame(root, padding=(16, 10, 16, 4))
        bar.grid(row=2, column=0, sticky="ew")
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

        # results
        body = ttk.Frame(root, padding=(16, 4, 16, 8))
        body.grid(row=3, column=0, sticky="nsew")
        body.columnconfigure(0, weight=3)
        body.columnconfigure(1, weight=1)
        body.rowconfigure(1, weight=1)

        self.progress = ttk.Progressbar(body, mode="determinate", maximum=1000)
        self.progress.grid(row=0, column=0, columnspan=2, sticky="ew", pady=(0, 8))

        lf = ttk.LabelFrame(body, text=" Results ", padding=4)
        lf.grid(row=1, column=0, sticky="nsew", padx=(0, 8))
        lf.rowconfigure(0, weight=1)
        lf.columnconfigure(0, weight=1)
        self.txt = tk.Text(lf, font=self.font_mono, bg=WIN_BG, relief="flat", borderwidth=0,
                           highlightthickness=0, wrap="none", state="disabled", height=12,
                           cursor="arrow")
        sb = ttk.Scrollbar(lf, orient="vertical", command=self.txt.yview)
        self.txt.configure(yscrollcommand=sb.set)
        self.txt.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.txt.tag_configure("free", foreground=WIN_GREEN,
                               font=(self.font_mono[0], 10, "bold"))
        self.txt.tag_configure("taken", foreground=WIN_RED)
        self.txt.tag_configure("info", foreground=WIN_GREY)

        af = ttk.LabelFrame(body, text=" Available names ", padding=4)
        af.grid(row=1, column=1, sticky="nsew")
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

        # status bar
        ttk.Separator(root, orient="horizontal").grid(row=4, column=0, sticky="ew")
        status = ttk.Frame(root, style="Status.TFrame", padding=(10, 3))
        status.grid(row=5, column=0, sticky="ew")
        self.lbl_status = ttk.Label(status, text="Ready", style="Status.TLabel")
        self.lbl_status.pack(side="left")
        ttk.Label(status, text="Available names are saved to available.txt next to this program",
                  style="Status.TLabel").pack(side="right")

        self._settings_widgets = [self.sp_min, self.sp_max, self.cb_letters, self.cb_digits,
                                  self.cb_under, self.rb_seq, self.rb_rand, self.e_start,
                                  self.sp_threads]
        root.bind("<F5>", lambda e: self.start())
        root.bind("<Escape>", lambda e: self.stop())
        root.protocol("WM_DELETE_WINDOW", self.on_close)

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
                import subprocess
                subprocess.Popen(["xdg-open", self.out_path])
        except Exception as e:
            messagebox.showerror(APP_NAME, str(e))

    def on_close(self):
        if self.checker:
            self.checker.stop()
        if self.out_file:
            self.out_file.close()
        self.root.destroy()

    # ------------------------------------------------------------------ updates
    def _status(self, text):
        self.lbl_status.configure(text=text)

    def _log(self, line, tag):
        self.txt.configure(state="normal")
        self.txt.insert("end", line + "\n", tag)
        self.txt.configure(state="disabled")

    def _poll(self):
        lines = []
        new_free = []
        status = None
        show_taken = self.v_show_taken.get()
        try:
            for _ in range(20000):
                ev = self.events.get_nowait()
                if ev[0] == "result":
                    _, name, free = ev
                    if free:
                        new_free.append(name)
                        lines.append((f"{name:<17}AVAILABLE", "free"))
                    elif show_taken:
                        lines.append((f"{name:<17}taken", "taken"))
                else:
                    status = ev[1]
        except queue.Empty:
            pass

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
            self.available_names.extend(new_free)
            self.lst.insert("end", *new_free)
            self.lst.see("end")
            if self.out_file:
                self.out_file.write("\n".join(new_free) + "\n")
                self.out_file.flush()

        if status:
            self._status(status)

        c = self.checker
        if c:
            self.stat_labels["checked"].configure(text=fmt_count(c.checked))
            self.stat_labels["available"].configure(text=fmt_count(c.available))
            self.stat_labels["taken"].configure(text=fmt_count(c.taken))
            self.stat_labels["speed"].configure(text=f"{c.speed():.0f}" if c.is_running() else "0")
            if self.total:
                done = (self.skipped + c.checked) / self.total
                self.progress.configure(value=min(1000, done * 1000))
            if not c.is_running() and self.btn_start.instate(["disabled"]) and self.events.empty():
                self._finished()

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
            self._status(f"Stopped. Checked {c.checked:,}, {c.available:,} available."
                         + (f"  Press Start to continue from {resume}." if resume else ""))
            self._log("Stopped." + (f" Will continue from {resume}." if resume else ""), "info")
        if self.out_file:
            self.out_file.close()
            self.out_file = None
        self._set_settings_state(True)
        self.btn_start.configure(state="normal")
        self.btn_stop.configure(state="disabled")
        if self.selftest:
            self._selftest_report()

    # ------------------------------------------------------------------ CI self-test
    def _selftest_report(self):
        c = self.checker
        report = os.path.join(app_dir(), "selftest.txt")
        with open(report, "w", encoding="utf-8") as f:
            f.write(f"checked={c.checked} available={c.available} taken={c.taken}\n")
            f.write(f"available_names={self.available_names[:50]}\n")
            f.write(f"status={self.lbl_status.cget('text')}\n")
        try:
            import importlib
            grab = importlib.import_module("PIL.ImageGrab")   # only present in CI
            self.root.update()
            x, y = self.root.winfo_rootx(), self.root.winfo_rooty()
            w, h = self.root.winfo_width(), self.root.winfo_height()
            grab.grab(bbox=(x, y, x + w, y + h)).save(os.path.join(app_dir(), "screenshot.png"))
        except Exception as e:
            with open(report, "a", encoding="utf-8") as f:
                f.write(f"screenshot failed: {e}\n")
        self.root.after(500, self.on_close)


def main():
    selftest = "--selftest" in sys.argv
    enable_dpi_awareness()
    root = tk.Tk()
    root.geometry("940x680")
    app = App(root, selftest=selftest)
    if selftest:
        app.v_letters.set(True)
        app.v_digits.set(False)
        app.v_min.set(4)
        app.v_max.set(4)
        app.v_start.set("qzaa")
        root.after(800, app.start)
        root.after(20000, app.stop)
    root.mainloop()


if __name__ == "__main__":
    main()
