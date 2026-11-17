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
| Letters / Numbers | which characters the names are built from |
| Underscores | **None**, **Anywhere** (`_ab`, `a__`), **At most 1, not at start/end** (`abc`, `ab_c`) or **Exactly 1, not at start/end** (`a_b`, `ab_c` - only underscore names) |
| Skip | skips names that were **taken or locked** when checked in the last N days (default 1), so re-running a range or continuing after Stop doesn't waste lookups on them. Free names are always re-checked |
| Order | in order (aaa, aab, ...) or random |
| Start from | continue where you left off - filled in automatically when you press Stop |
| Parallel requests | how many lookups run at once (default 12) |
| Pacing | **Smooth** (default) learns where Mojang's limit is and stays just under it, with pauses of at most 10 s. **Max** pushes past the limit and gets paused for longer and longer (up to 2 min). In a real 2-minute test Smooth checked 34-55% more names, because Max ended up stalled |
| Show taken names | turn off to only see the green ones |

### Alerts

| Option | What it does |
|---|---|
| Alert for free names **up to / exactly** **3** letters | e.g. "exactly 3" alerts only for 3-letter names, "up to 4" for 3- and 4-letter ones. Hits get a green bar at the top and are highlighted |
| Only unlocked names | only alert for names the locked check (below) confirmed are really free - needs the Minecraft login |
| Stop when found | stops searching as soon as an alert fires, so you can go claim the name |
| Notification + sound | a Windows notification pops up, it plays a sound and the taskbar button blinks |
| Discord | posts each alert to a Discord channel - see below |

### Discord alerts

1. In Discord: **Server Settings -> Integrations -> Webhooks -> New Webhook**, pick a channel, **Copy Webhook URL**.
2. In the checker: tick **Discord** (or press **Webhook...**), paste the URL, press **Send test message**, then **Save**.

Every alert is then also posted to that channel (several finds within a few seconds are combined into one
message), with each name's length and whether the locked check confirmed it. Tick **Mention @everyone** to ping
the channel. The URL is saved in `settings.json` next to the program - keep it private, anyone with it can post
in that channel.

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

- skips names it already found taken/locked recently (see **Skip**)
- jumps straight to "Start from" even deep into long names, instead of stepping through every earlier name

The real limit is Mojang's: they cap how many lookups one internet connection can make.

## Notes

"Available" means no account has the name. Without the locked check above, a few of those still can't be claimed (blocked words, or names
someone changed away from in the last 37 days), so double-check in the launcher.

## Command-line version

`python mc_name_checker.py --help` - same engine, coloured terminal output. Example:
`python mc_name_checker.py --min 4 --max 4 --underscores max1 --random`.

Running from source needs Python 3 only (`python mc_name_checker_gui.py` for the window).
