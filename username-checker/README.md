# Minecraft short-username checker

Goes through 3 and 4 character Minecraft usernames and checks each one with Mojang.
Available names show in **green**, taken ones in **red**. Available names are also saved to `available.txt`.

Needs Python 3 and nothing else. Run it from a terminal:

```
python mc_name_checker.py
```

Options:

| Flag | What it does |
|---|---|
| `--letters` | letters a-z only (default also uses 0-9 and `_`) |
| `--length 3` / `--length 4` | only check one length |
| `--random` | check names in random order instead of aaa, aab, aac... |
| `--start abcd` | resume from a name (the program prints this when you stop it) |
| `--only-available` | hide taken names |
| `--delay 1.0` | seconds between requests (each request checks 10 names) |

Press Ctrl+C to stop.

Note: "available" means Mojang has no account with that name. A few of those still can't be claimed
(banned/blocked words, or names someone changed away from in the last 37 days), so try it in the
launcher to be sure. Mojang rate-limits lookups; the program waits and retries automatically.
