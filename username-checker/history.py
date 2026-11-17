"""
Remembers every name that has been checked (in a small SQLite file next to
the program) so you can search them later: taken, available, or both.
"""

import sqlite3
import time

COUNT_CAP = 1_000_000      # counting stops here so searches stay fast

SCHEMA = """
CREATE TABLE IF NOT EXISTS names (
    name      TEXT PRIMARY KEY,
    available INTEGER NOT NULL,
    checked   REAL NOT NULL
) WITHOUT ROWID
"""


class History:
    def __init__(self, path):
        self.db = sqlite3.connect(path, timeout=10)
        self.db.execute("PRAGMA journal_mode=WAL")      # searches can read while we write
        self.db.execute("PRAGMA synchronous=NORMAL")
        self.db.execute(SCHEMA)
        self.db.commit()

    def add_many(self, rows):
        """rows: iterable of (name, available: bool)."""
        now = time.time()
        self.db.executemany("INSERT OR REPLACE INTO names VALUES (?, ?, ?)",
                            [(n.lower(), int(bool(a)), now) for n, a in rows])
        self.db.commit()

    def get(self, name):
        """(available: bool, checked_time) or None if never checked."""
        row = self.db.execute("SELECT available, checked FROM names WHERE name = ?",
                              (name.lower(),)).fetchone()
        return (bool(row[0]), row[1]) if row else None

    @staticmethod
    def _where(term, available):
        parts, args = [], []
        if term:
            parts.append("instr(name, ?) > 0")             # plain "contains" - no wildcards
            args.append(term.lower())
        if available is not None:
            parts.append("available = ?")
            args.append(int(available))
        return (" WHERE " + " AND ".join(parts)) if parts else "", args

    def search(self, term="", available=None, limit=500):
        """Names containing `term`. available: None = both, True = free only, False = taken only."""
        where, args = self._where(term, available)
        return self.db.execute(f"SELECT name, available, checked FROM names{where} "
                               f"ORDER BY name LIMIT ?", args + [limit]).fetchall()

    def count(self, term="", available=None, cap=COUNT_CAP):
        where, args = self._where(term, available)
        return self.db.execute(f"SELECT COUNT(*) FROM (SELECT 1 FROM names{where} LIMIT ?)",
                               args + [cap]).fetchone()[0]

    def totals(self):
        total, free = self.db.execute("SELECT COUNT(*), COALESCE(SUM(available), 0) FROM names"
                                      ).fetchone()
        return total, free

    def clear(self):
        self.db.execute("DELETE FROM names")
        self.db.commit()
        self.db.execute("VACUUM")

    def close(self):
        self.db.close()
