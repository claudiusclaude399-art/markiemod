"""Translations for the PC Usage Limiter (English + Slovenian / Slovenščina)."""

TRANSLATIONS = {
    # key: (English, Slovenian)
    "app_name":            ("PC Usage Limiter", "Omejevalnik uporabe računalnika"),

    # Lock screen
    "locked_title":        ("Computer Locked", "Računalnik je zaklenjen"),
    "locked_limit_msg":    ("You have reached today's time limit.",
                            "Dosegli ste današnjo časovno omejitev."),
    "locked_schedule_msg": ("Computer use is not allowed during these hours.",
                            "Uporaba računalnika v tem času ni dovoljena."),
    "locked_manual_msg":   ("The computer has been locked manually.",
                            "Računalnik je bil ročno zaklenjen."),
    "enter_code":          ("Enter unlock code:", "Vnesite kodo za odklepanje:"),
    "unlock_button":       ("Unlock", "Odkleni"),
    "wrong_code":          ("Wrong code. Try again.", "Napačna koda. Poskusite znova."),
    "attempts_left":       ("Attempts before cooldown: {n}",
                            "Poskusov do zakasnitve: {n}"),
    "cooldown_msg":        ("Too many attempts. Wait {s} seconds.",
                            "Preveč poskusov. Počakajte {s} sekund."),
    "time_used_today":     ("Time used today:", "Danes porabljen čas:"),
    "time_remaining":      ("Time remaining:", "Preostali čas:"),
    "current_time":        ("Current time:", "Trenutni čas:"),

    # Tray / status
    "tray_settings":       ("Settings", "Nastavitve"),
    "tray_status":         ("Status", "Stanje"),
    "tray_lock_now":       ("Lock now", "Zakleni zdaj"),
    "tray_quit":           ("Quit", "Izhod"),
    "status_running":      ("PC Usage Limiter is running.",
                            "Omejevalnik uporabe računalnika se izvaja."),

    # Settings window
    "settings_title":      ("Settings", "Nastavitve"),
    "admin_password":      ("Admin password:", "Skrbniško geslo:"),
    "login":               ("Log in", "Prijava"),
    "wrong_password":      ("Wrong password.", "Napačno geslo."),
    "daily_limit_label":   ("Daily limit (minutes):", "Dnevna omejitev (minute):"),
    "per_day_limits_label": ("Daily limits (minutes, 0 = no limit):",
                             "Dnevne omejitve (minute, 0 = brez omejitve):"),
    "weekday_limit_label": ("Weekday limit (minutes):",
                            "Omejitev za delovni dan (minute):"),
    "weekend_limit_label": ("Weekend limit (minutes):",
                            "Omejitev za vikend (minute):"),
    "apply_weekday":       ("Apply to Mon–Fri", "Uporabi za pon.–pet."),
    "apply_weekend":       ("Apply to Sat–Sun", "Uporabi za sob.–ned."),
    "idle_timeout_label":  ("Pause counting after inactivity (seconds, 0 = off):",
                            "Zaustavi štetje po nedejavnosti (sekunde, 0 = izklop):"),
    "day_mon":             ("Monday", "Ponedeljek"),
    "day_tue":             ("Tuesday", "Torek"),
    "day_wed":             ("Wednesday", "Sreda"),
    "day_thu":             ("Thursday", "Četrtek"),
    "day_fri":             ("Friday", "Petek"),
    "day_sat":             ("Saturday", "Sobota"),
    "day_sun":             ("Sunday", "Nedelja"),
    "grace_label":         ("Extra time per unlock (minutes):",
                            "Dodatni čas ob vsakem odklepu (minute):"),
    "blocked_hours_label": ("Blocked hours (e.g. 22:00-07:00, comma separated):",
                            "Blokirani čas (npr. 22:00-07:00, ločeno z vejico):"),
    "unlock_code_label":   ("Unlock code (leave blank to keep):",
                            "Koda za odklepanje (pustite prazno, da ostane enaka):"),
    "admin_pw_label":      ("Admin password (leave blank to keep):",
                            "Skrbniško geslo (pustite prazno, da ostane enako):"),
    "language_label":      ("Language:", "Jezik:"),
    "block_tm_label":      ("Block Task Manager while locked",
                            "Blokiraj Upravitelja opravil med zaklepom"),
    "autostart_label":     ("Run at Windows startup", "Zaženi ob zagonu sistema Windows"),
    "save":                ("Save", "Shrani"),
    "saved":               ("Settings saved.", "Nastavitve so shranjene."),
    "quit_app":            ("Quit program", "Zapri program"),
    "show_master":         ("Show recovery code", "Pokaži obnovitveno kodo"),

    # Setup wizard
    "setup_title":         ("First-time setup", "Prva namestitev"),
    "setup_intro":         ("Set your codes and limits. Keep the recovery code safe!",
                            "Nastavite kode in omejitve. Obnovitveno kodo shranite na varno!"),
    "set_admin_pw":        ("Admin password (opens settings):",
                            "Skrbniško geslo (odpira nastavitve):"),
    "set_unlock_code":     ("Daily unlock code:", "Dnevna koda za odklepanje:"),
    "master_code_label":   ("Recovery code (always works):",
                            "Obnovitvena koda (vedno deluje):"),
    "master_code_generated": ("Your recovery code — WRITE IT DOWN:",
                              "Vaša obnovitvena koda — ZAPIŠITE SI JO:"),
    "finish_setup":        ("Finish setup", "Dokončaj namestitev"),
    "english":             ("English", "Angleščina"),
    "slovenian":           ("Slovenian", "Slovenščina"),
    "minutes_short":       ("min", "min"),

    # Messages
    "grace_granted":       ("Unlocked. You have extra time now.",
                            "Odklenjeno. Zdaj imate dodatni čas."),
    "master_unlocked":     ("Unlocked with recovery code.",
                            "Odklenjeno z obnovitveno kodo."),
    "fields_required":     ("Please fill in all required fields.",
                            "Izpolnite vsa obvezna polja."),
    "invalid_time":        ("Invalid time format. Use HH:MM-HH:MM.",
                            "Neveljaven zapis časa. Uporabite HH:MM-HH:MM."),
    "confirm_quit":        ("Really quit the limiter? Limits will stop until next startup.",
                            "Res zapreti omejevalnik? Omejitve ne bodo delovale do naslednjega zagona."),
    "yes":                 ("Yes", "Da"),
    "no":                  ("No", "Ne"),
}


class Translator:
    """Small helper that returns strings in the active language."""

    def __init__(self, lang="en"):
        self.lang = lang if lang in ("en", "sl") else "en"

    def set_lang(self, lang):
        self.lang = lang if lang in ("en", "sl") else "en"

    def t(self, key, **kwargs):
        pair = TRANSLATIONS.get(key)
        if not pair:
            return key
        text = pair[1] if self.lang == "sl" else pair[0]
        if kwargs:
            try:
                text = text.format(**kwargs)
            except (KeyError, IndexError, ValueError):
                pass
        return text
