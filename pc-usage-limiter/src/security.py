"""Password / code hashing. Codes are never stored in plain text."""

import hashlib
import hmac
import os
import secrets
import string

_ITERATIONS = 200_000


def hash_code(code: str) -> dict:
    """Return a salted PBKDF2 hash record for a code/password."""
    salt = secrets.token_hex(16)
    dk = hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"),
                             bytes.fromhex(salt), _ITERATIONS)
    return {"salt": salt, "hash": dk.hex(), "iter": _ITERATIONS}


def verify_code(code: str, record: dict) -> bool:
    """Constant-time check of a code against a stored record."""
    if not record or "salt" not in record or "hash" not in record:
        return False
    try:
        iterations = int(record.get("iter", _ITERATIONS))
        dk = hashlib.pbkdf2_hmac("sha256", code.encode("utf-8"),
                                 bytes.fromhex(record["salt"]), iterations)
        return hmac.compare_digest(dk.hex(), record["hash"])
    except (ValueError, TypeError):
        return False


def generate_recovery_code(groups: int = 4, group_len: int = 4) -> str:
    """Human-friendly recovery code like 'A7K2-9QX4-...' (no confusing chars)."""
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  # no I,O,0,1
    parts = []
    for _ in range(groups):
        parts.append("".join(secrets.choice(alphabet) for _ in range(group_len)))
    return "-".join(parts)
