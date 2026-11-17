"""
Discord webhook alerts.

Paste a channel's webhook URL (Server Settings -> Integrations -> Webhooks) and
the checker posts a message there when it finds a name worth alerting about.
"""

import datetime
import json
import re
import threading
import time

import checker_core as core

WEBHOOK_RE = re.compile(
    r"^https://(?:(?:ptb|canary)\.)?discord(?:app)?\.com/api(?:/v\d+)?/webhooks/\d+/[\w-]+/?$")
BOT_NAME = "Minecraft Username Checker"
GREEN = 0x107C10
MAX_LISTED = 20

_send_lock = threading.Lock()          # one message at a time, in order


def is_webhook_url(url):
    return bool(WEBHOOK_RE.match((url or "").strip()))


def build_message(names, verified=None, everyone=False, test=False):
    """
    names: list of names to announce.
    verified: {name: True (locked check says free) / False (lock not checked)}.
    """
    verified = verified or {}
    now = datetime.datetime.now(datetime.timezone.utc).isoformat()
    if test:
        embed = {
            "title": "Test message",
            "description": "Discord alerts from the Minecraft Username Checker are working.",
            "color": GREEN,
        }
    else:
        lines = []
        for n in names[:MAX_LISTED]:
            if n in verified:
                lock = ("✅ not locked" if verified[n] else "⚠️ lock not checked")
                lines.append(f"**`{n}`**  ({len(n)} letters) - {lock}")
            else:
                lines.append(f"**`{n}`**  ({len(n)} letters)")
        if len(names) > MAX_LISTED:
            lines.append(f"... and {len(names) - MAX_LISTED} more")
        title = (f"{names[0]} is available!" if len(names) == 1
                 else f"{len(names)} names available!")
        embed = {"title": title, "description": "\n".join(lines), "color": GREEN}
    embed["footer"] = {"text": BOT_NAME}
    embed["timestamp"] = now
    return {
        "username": BOT_NAME,
        "content": "@everyone" if everyone else "",
        "allowed_mentions": {"parse": ["everyone"] if everyone else []},
        "embeds": [embed],
    }


def send(url, payload, tries=4):
    """Post to the webhook. Returns (ok, message). Waits and retries if Discord rate-limits."""
    url = url.strip()
    body = json.dumps(payload).encode()
    with _send_lock:
        for _ in range(tries):
            try:
                status, data = core._request("POST", url, body)
            except core.LookupFailed as e:
                return False, f"couldn't reach Discord ({e})"
            if 200 <= status < 300:
                return True, "sent"
            if status == 429:
                try:
                    wait = float(json.loads(data).get("retry_after", 1))
                except (ValueError, AttributeError):
                    wait = 1.0
                if wait > 1000:                 # some replies give milliseconds
                    wait /= 1000
                time.sleep(min(max(wait, 0.5), 30))
                continue
            if status in (401, 404):
                return False, "Discord says this webhook doesn't exist - check the URL"
            return False, f"Discord answered HTTP {status}"
        return False, "Discord kept rate-limiting the webhook"
