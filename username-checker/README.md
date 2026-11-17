# Minecraft Username Checker

Windows app that goes through Minecraft usernames and checks which are free.
**Green = available, yellow = locked, red = taken.** Available names are also saved to `available.txt` next to the program.

## Getting the .exe

1. On GitHub open the **Actions** tab and pick the latest **username checker exe** run.
2. Download the **MinecraftUsernameChecker-exe** artifact (a zip), unzip it and run `MinecraftUsernameChecker.exe`.

Windows may show "Windows protected your PC" because the exe isn't signed: click **More info → Run anyway**.

## Options

| Option | What it does |
|---|---|
| Username length | e.g. 3 to 4, or 5 to 5. Minecraft allows 3-16 characters. |
| Letters / Numbers / Underscores | which characters the names are built from |
| Order | in order (aaa, aab, ...) or random |
| Start from | continue where you left off - filled in automatically when you press Stop |
| Parallel requests | how many lookups run at once (default 12) |
| Show taken names | turn off to only see the green ones |

### Short-name alerts

| Option | What it does |
|---|---|
| Alert for free names up to **3** letters | highlights them and shows a green bar at the top (change 3 to any length) |
| Windows notification + sound | a Windows notification pops up, it plays a sound and the taskbar button blinks |
| Stop searching when one is found | stops as soon as one turns up, so you can go claim it |

### Locked names (yellow)

A name nobody owns can still be **locked**: blocked by Mojang, or on hold for a while after someone changed
away from it. The normal lookups can't see that. Mojang's logged-in check (the one the launcher uses when you
change your name) can, so this option needs your Minecraft access token:

1. Tick **Check that free names aren't locked** (or press **Log in...**).
2. Follow the steps in the window to copy your token from minecraft.net, paste it and press **Test and use**.

Free names are then checked a second time. Really free ones stay **green** ("not locked"), locked ones turn
**yellow** with the reason, and only really free names go into `available.txt` and trigger alerts.
Mojang allows far fewer of these checks, so they run in their own slower lane (shortest names first). The
"Locked" counter shows how many are still waiting. **Check now** in the Search tab uses it too.

The token is kept in memory only and only sent to Mojang. It runs out after about 24 hours; the app then turns
the check off, marks names as "lock not checked" and asks you to log in again. Keep it private: anyone who
has it can use your Minecraft account until it runs out.

### Searching checked names

Every name the program checks is remembered in `checked_names.db` (next to the program), including from earlier runs.
In the **Search checked names** tab, type part of a name: `ab` finds `abc`, `xab9`, `ab_1`...
Filter by **All / Available / Locked / Taken**. If you type a full name that hasn't been checked yet, press **Check now**
(or Enter) to look it up straight away. **Forget all checked names** wipes the list.

Shortcuts: **F5** start, **Esc** stop, **Ctrl+F** search. Select names and press **Ctrl+C** to copy.

## How it's fast

- checks 10 names per request, with many requests running in parallel
- reuses connections instead of opening a new one per request
- spreads requests over several Mojang servers. Each one's speed tunes itself:
  it speeds up until Mojang says "slow down", pauses just that server briefly,
  and keeps going on the others.

The real limit is Mojang's: they cap how many lookups one internet connection can make.

## Notes

"Available" means no account has the name. Without the locked check above, a few of those still can't be claimed (blocked words, or names
someone changed away from in the last 37 days), so double-check in the launcher.

## Command-line version

`python mc_name_checker.py --help` - same engine, coloured terminal output. Example:
`python mc_name_checker.py --min 4 --max 4 --underscores --random`.

Running from source needs Python 3 only (`python mc_name_checker_gui.py` for the window).
