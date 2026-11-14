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

DEFAULT_CONFIG = {
    "version": 1,
    "first_run": True,
    "language": "en",
    "daily_limit_minutes": 120,       # 0 = no daily time limit
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


def load_config() -> dict:
    cfg = dict(DEFAULT_CONFIG)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as fh:
            cfg.update(json.load(fh))
    except (FileNotFoundError, json.JSONDecodeError, OSError):
        pass
    return cfg


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
