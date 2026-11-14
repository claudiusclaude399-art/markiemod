# PC Usage Limiter / Omejevalnik uporabe računalnika

A small Windows app that limits how long **your own PC** is used, locks the
screen when a limit is reached (or during blocked hours), and unlocks with a
code you choose. It starts automatically with Windows and lives quietly in the
system tray. English and Slovenian are both built in.

> This is a self-control / parental screen-time tool for a computer you own or
> administer. It is **not** stealthy and is always removable: a **recovery
> code**, **Safe Mode**, and `uninstall.bat` each get you out, so you can never
> lock yourself out permanently.

---

## English

### Features
- **Locks the PC** with a full-screen overlay (stays on top, blocks Alt+F4 and
  the close button; optionally blocks Alt+Tab / Win and Task Manager).
- **Per-day time limits** — a separate daily limit for each weekday (Mon–Sun).
  Defaults to **2 h on weekdays, 4 h on weekends**; set each day individually,
  or use the quick *Apply to Mon–Fri / Sat–Sun* buttons. `0` = no limit that day.
- **Idle pause** — time only counts while you're actually using the PC. After
  the configured inactivity timeout (default 60 s with no keyboard/mouse input)
  the daily counter pauses, and resumes when you come back.
- **Blocked hours** — e.g. `22:00-07:00` (bedtime), supports windows that cross
  midnight; add several, comma-separated.
- **Your own unlock code** to dismiss the lock (grants a configurable amount of
  extra time).
- **Recovery code** that always works and turns locking off until midnight —
  your safety net. Shown once during setup: **write it down.**
- **Emergency code `unl_123_e`** — a fixed, always-valid code that unlocks
  anything (same effect as the recovery code). Handy if you forget the others;
  change `EMERGENCY_CODE` in `src/main.py` before building if you want a
  different one.
- **Admin password** that protects Settings and quitting the app.
- **Runs at Windows startup** (per-user, no admin rights needed).
- **System tray** menu: Status, Lock now, Settings, Quit.
- **English + Slovenian**, switchable any time in Settings.
- Codes/passwords are stored only as salted **PBKDF2-SHA256 hashes**, never in
  plain text.

### Build the .exe (on Windows)
1. Install [Python 3](https://www.python.org/downloads/) and tick
   *"Add Python to PATH"*.
2. Double-click **`build.bat`**. It installs the dependencies and produces
   **`dist\PCUsageLimiter.exe`** (a single file).

### Install (start with Windows)
- Double-click **`install.bat`**. It copies the exe to
  `%LOCALAPPDATA%\PCUsageLimiter`, registers it to run at login, and launches it.
- On first run you set your language, admin password, daily unlock code and
  daily limit, and you are shown your **recovery code**.

Want to try it before building? Run **`run_from_source.bat`** (needs Python).

### Daily use
- The app sits in the tray. When the daily limit is hit or a blocked hour
  starts, the lock screen appears.
- Type your **unlock code** → you get extra time (default 10 min).
- Type your **recovery code** → locking is disabled until midnight.
- Open **Settings** from the tray (needs the admin password) to change limits,
  hours, codes, language, Task-Manager blocking, and autostart.

### Remove it (escape hatch)
- Run **`uninstall.bat`** — stops the app, removes the startup entry, restores
  Task Manager, and deletes the program. It offers to keep or delete your
  settings.
- Forgot everything? Boot Windows in **Safe Mode** (startup apps don't run),
  then run `uninstall.bat` or delete `%APPDATA%\PCUsageLimiter`.

### Where things are stored
`%APPDATA%\PCUsageLimiter\` — `config.json` (settings + hashed codes),
`usage.json` (today's time), `usage.log` (lock/unlock history).

---

## Slovensko

### Funkcije
- **Zaklene računalnik** s celozaslonskim prekrivom (ostane na vrhu, blokira
  Alt+F4 in gumb za zapiranje; izbirno blokira Alt+Tab / tipko Windows in
  Upravitelja opravil).
- **Dnevne omejitve po dnevih** — ločena omejitev za vsak dan (pon.–ned.).
  Privzeto **2 h med tednom, 4 h za vikend**; nastavite vsak dan posebej ali
  uporabite hitra gumba *Uporabi za pon.–pet. / sob.–ned.*. `0` = brez omejitve
  tisti dan.
- **Zaustavitev ob nedejavnosti** — čas se šteje le, ko računalnik dejansko
  uporabljate. Po nastavljeni nedejavnosti (privzeto 60 s brez tipkovnice/miške)
  se števec zaustavi in se nadaljuje, ko se vrnete.
- **Blokirani čas** — npr. `22:00-07:00` (spanje); deluje tudi čez polnoč; več
  obdobij ločite z vejico.
- **Lastna koda za odklepanje**, ki odpre zaklep in doda nekaj dodatnega časa.
- **Obnovitvena koda**, ki vedno deluje in izklopi zaklepanje do polnoči — vaša
  varnostna mreža. Prikaže se enkrat ob namestitvi: **zapišite si jo.**
- **Koda za nujne primere `unl_123_e`** — fiksna koda, ki vedno odklene vse
  (enak učinek kot obnovitvena koda). Uporabna, če pozabite druge; pred izdelavo
  spremenite `EMERGENCY_CODE` v `src/main.py`, če želite drugačno.
- **Skrbniško geslo**, ki ščiti Nastavitve in izhod iz programa.
- **Samodejni zagon ob prijavi v Windows** (za trenutnega uporabnika, brez
  skrbniških pravic).
- **Sistemska vrstica**: Stanje, Zakleni zdaj, Nastavitve, Izhod.
- **Angleščina + slovenščina**, preklopljivo v Nastavitvah.
- Kode in gesla so shranjena le kot soljeni **PBKDF2-SHA256 povzetki**, nikoli v
  navadnem besedilu.

### Izdelava .exe (v sistemu Windows)
1. Namestite [Python 3](https://www.python.org/downloads/) in obkljukajte
   *"Add Python to PATH"*.
2. Dvokliknite **`build.bat`**. Namesti odvisnosti in ustvari
   **`dist\PCUsageLimiter.exe`** (ena sama datoteka).

### Namestitev (zagon ob zagonu sistema Windows)
- Dvokliknite **`install.bat`**. Datoteko kopira v
  `%LOCALAPPDATA%\PCUsageLimiter`, jo registrira za zagon ob prijavi in jo zažene.
- Ob prvem zagonu nastavite jezik, skrbniško geslo, dnevno kodo za odklepanje in
  dnevno omejitev ter si zapišete **obnovitveno kodo**.

Želite preizkusiti pred izdelavo? Zaženite **`run_from_source.bat`** (potrebuje
Python).

### Vsakodnevna uporaba
- Program je v sistemski vrstici. Ko dosežete dnevno omejitev ali se začne
  blokirani čas, se pojavi zaklenjeni zaslon.
- Vnesite **kodo za odklepanje** → dobite dodatni čas (privzeto 10 min).
- Vnesite **obnovitveno kodo** → zaklepanje je izklopljeno do polnoči.
- V **Nastavitvah** (potrebujete skrbniško geslo) spremenite omejitve, čas,
  kode, jezik, blokiranje Upravitelja opravil in samodejni zagon.

### Odstranitev (izhod v sili)
- Zaženite **`uninstall.bat`** — ustavi program, odstrani vnos za samodejni
  zagon, obnovi Upravitelja opravil in izbriše program. Ponudi ohranitev ali
  izbris vaših nastavitev.
- Ste pozabili vse? Zaženite Windows v **varnem načinu** (zagonski programi se
  ne zaženejo), nato zaženite `uninstall.bat` ali izbrišite mapo
  `%APPDATA%\PCUsageLimiter`.

### Kje so shranjeni podatki
`%APPDATA%\PCUsageLimiter\` — `config.json` (nastavitve + povzetki kod),
`usage.json` (današnji čas), `usage.log` (zgodovina zaklepanja).

---

### Notes & limits
- Tkinter (the GUI) and `winreg` (startup/registry) ship with standard Windows
  Python. The tray icon uses `pystray` + `Pillow`; stronger key-blocking uses
  `keyboard` and needs admin rights. If those optional packages are missing, the
  app still runs — it shows a small status window instead of a tray icon, and
  relies on the always-on-top overlay instead of global key hooks.
- This is a deterrent/limiter, not unbreakable security. A determined expert
  with full admin access and physical control of the machine can bypass any
  user-level locker — by design, so you keep control of your own PC.
