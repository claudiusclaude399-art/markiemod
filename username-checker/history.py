"""
Remembers every name that has been checked (in a small SQLite file next to
the program) so you can search them later: taken, available, locked.
"""

import sqlite3
import time

COUNT_CAP = 1_000_000      # counting stops here so searches stay fast

# values of the "available" column (0/1 kept from older versions of the file)
TAKEN = 0
FREE = 1
BLOCKED = 2                # locked: Mojang doesn't allow the name
HELD = 3                   # locked: on hold after a name change

LOCKED = (BLOCKED, HELD)

SCHEMA = """
CREATE TABLE IF NOT EXISTS names (
    name      TEXT PRIMARY KEY,
    available INTEGER NOT NULL,     -- TAKEN / FREE / BLOCKED / HELD
    checked   REAL NOT NULL
) WITHOUT ROWID
"""


class History:
    def __init__(self, path, any_thread=False):
        # any_thread: the caller promises to use it from one thread at a time
        self.db = sqlite3.connect(path, timeout=10, check_same_thread=not any_thread)
        self.db.execute("PRAGMA journal_mode=WAL")      # searches can read while we write
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.execute(SCHEMA)
        self.db.commit()

    def add_many(self, rows):
        """rows: iterable of (name, status) - status is TAKEN/FREE/BLOCKED/HELD (or a bool)."""
        now = time.time()
        self.db.executemany("INSERT OR REPLACE INTO names VALUES (?, ?, ?)",
                            [(n.lower(), int(st), now) for n, st in rows])
        self.db.commit()

    def get(self, name):
        """(status, checked_time) or None if never checked."""
        row = self.db.execute("SELECT available, checked FROM names WHERE name = ?",
                              (name.lower(),)).fetchone()
        return (row[0], row[1]) if row else None

    @staticmethod
    def _where(term, statuses):
        parts, args = [], []
        if term:
            parts.append("instr(name, ?) > 0")             # plain "contains" - no wildcards
            args.append(term.lower())
        if statuses is not None:
            parts.append(f"available IN ({','.join('?' * len(statuses))})")
            args.extend(int(s) for s in statuses)
        return (" WHERE " + " AND ".join(parts)) if parts else "", args

    def search(self, term="", statuses=None, limit=500):
        """Names containing `term`; statuses = None for all, or e.g. (FREE,) or LOCKED."""
        where, args = self._where(term, statuses)
        return self.db.execute(f"SELECT name, available, checked FROM names{where} "
                               f"ORDER BY name LIMIT ?", args + [limit]).fetchall()

    def count(self, term="", statuses=None, cap=COUNT_CAP):
        where, args = self._where(term, statuses)
        return self.db.execute(f"SELECT COUNT(*) FROM (SELECT 1 FROM names{where} LIMIT ?)",
                               args + [cap]).fetchone()[0]

    def recently_checked(self, names, since, statuses=(TAKEN, BLOCKED, HELD)):
        """The subset of `names` checked after `since` (epoch seconds) with one of `statuses`."""
        names = [n.lower() for n in names]
        found = set()
        st = ",".join(str(int(x)) for x in statuses)
        for i in range(0, len(names), 500):
            part = names[i:i + 500]
            rows = self.db.execute(
                f"SELECT name FROM names WHERE name IN ({','.join('?' * len(part))}) "
                f"AND checked >= ? AND available IN ({st})", part + [since]).fetchall()
            found.update(r[0] for r in rows)
        return found

    def totals(self):
        """(checked, available, locked)"""
        return self.db.execute(
            "SELECT COUNT(*), COALESCE(SUM(available = 1), 0), COALESCE(SUM(available >= 2), 0) "
            "FROM names").fetchone()

    def clear(self):
        self.db.execute("DELETE FROM names")
        self.db.commit()
        self.db.execute("VACUUM")

    def close(self):
        self.db.close()
