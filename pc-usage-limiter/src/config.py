"""Configuration + usage persistence.

Everything lives in %APPDATA%\\PCUsageLimiter on Windows
(or ~/.pcusagelimiter elsewhere, which is handy for testing on Linux/Mac).
"""

import json
import os
from datetime import date

APP_DIRNAME = "PCUsageLimiter"


def data_dir() -> str:
    base = os.environ.get("APPDATA")
    if not base:  # non-Windows fallback (dev/testing)
        base = os.path.join(os.path.expanduser("~"), ".config")
    path = os.path.join(base, APP_DIRNAME) if base else \
        os.path.join(os.path.expanduser("~"), "." + APP_DIRNAME.lower())
    os.makedirs(path, exist_ok=True)
    return path


CONFIG_PATH = os.path.join(data_dir(), "config.json")
USAGE_PATH = os.path.join(data_dir(), "usage.json")
LOG_PATH = os.path.join(data_dir(), "usage.log")

# Day keys match Python's date.weekday(): Monday=0 .. Sunday=6
DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
WEEKDAYS = DAYS[:5]
WEEKEND = DAYS[5:]

DEFAULT_CONFIG = {
    "version": 2,
    "first_run": True,
    "language": "en",
    # Per-day limit in minutes (0 = no limit that day). Default: 2h weekdays,
    # 4h weekends, exactly as requested.
    "daily_limits": {
        "mon": 120, "tue": 120, "wed": 120, "thu": 120, "fri": 120,
        "sat": 240, "sun": 240,
    },
    "idle_timeout_seconds": 60,       # pause counting after this much inactivity
    "grace_minutes": 10,              # extra time granted per successful unlock
    "blocked_windows": [],            # e.g. [{"start": "22:00", "end": "07:00"}]
    "block_task_manager": False,      # reversible, only while locked
    "autostart": True,
    "max_attempts": 5,                # wrong tries before a cooldown
    "cooldown_seconds": 30,
    # security records (filled during setup)
    "admin_password": None,
    "unlock_code": None,
    "master_code": None,
}


def _migrate(cfg: dict) -> dict:
    """Bring older config files (v1 single limit) up to the current shape."""
    if "daily_limits" not in cfg or not isinstance(cfg.get("daily_limits"), dict):
        old = cfg.get("daily_limit_minutes", 120)
        try:
            old = int(old)
        except (ValueError, TypeError):
            old = 120
        cfg["daily_limits"] = {d: old for d in DAYS}
    # make sure every day key exists
    for d in DAYS:
        cfg["daily_limits"].setdefault(d, 120)
    cfg.pop("daily_limit_minutes", None)
    cfg.setdefault("idle_timeout_seconds", 60)
    return cfg


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    cfg["daily_limits"] = dict(DEFAULT_CONFIG["daily_limits"])
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
            cfg.update(json.load(fh))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        pass
    return _migrate(cfg)


def save_config(cfg: dict) -> None:
    tmp = CONFIG_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(cfg, fh, indent=2, ensure_ascii=False)
    os.replace(tmp, CONFIG_PATH)


def load_usage() -> dict:
    """Return {'date': 'YYYY-MM-DD', 'seconds': int} for *today*."""
    today = date.today().isoformat()
    try:
        with open(USAGE_PATH, "r", encoding="utf-8") as fh:
            data = json.load(fh)
        if data.get("date") == today:
            return {"date": today, "seconds": int(data.get("seconds", 0))}
    except (FileNotFoundError, json.JSONDecodeError, OSError, ValueError, TypeError):
        pass
    return {"date": today, "seconds": 0}


def save_usage(usage: dict) -> None:
    tmp = USAGE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(usage, fh)
    os.replace(tmp, USAGE_PATH)
