# Paper plugins (Paper 1.21.11 server)

Two server plugins. Drop the jars into `plugins/` and restart. No other plugins are needed.

| Plugin | What it does | Menu |
|---|---|---|
| **GriefCheck** | Logs block, chest, fire, explosion and kill activity, then tells you who griefed a base, with rollback | `/gc` |
| **AdminGuard** | Makes powerful commands need a confirm, a PIN or approval from another staff member, and keeps an audit log | `/ag` |

**Build:** push to GitHub. The *paper-plugins* workflow builds both jars (artifacts `GriefCheck` and `AdminGuard`).
To build locally, run `gradle build` in this folder. You need Java 21. The jars end up in `*/build/libs/`.

---

## GriefCheck

Everything happens in the GUI (`/gc`). All settings can be changed in `/gc settings` and are saved to `config.yml`.

**"Who griefed this base?"** (`/gc report`) ranks every player who changed anything near you by a
*suspicion score*. The score counts most heavily what they broke, blew up, burned or stole that **someone else placed**.
- Left-click a player to see exactly what they did there.
- Right-click to roll back all their changes in that area.

**Inspector** (`/gc inspect`):
- Left-click a block to see its history.
- Right-click a chest to see who took or added what.
- Right-click a block face to inspect the empty space in front of it.
- Sneak to build normally while inspecting.

**Lookup** (`/gc lookup`) is a paged GUI with filters for action type, time, radius and player.
- Click an entry to teleport there.
- The TNT button rolls back (or restores) everything that matches the filters.

**Grief alerts:** when a player breaks or loots a configurable number of things owned by other players within a time
window, staff with `griefcheck.alerts` get a chat alert with clickable `[Teleport] [Lookup] [Report]` buttons.
Players can `/gc trust <friend>` so building partners don't set off alerts.

**What gets logged** (each can be switched on or off):
- Block break and place, including both halves of doors and beds
- Chest, barrel, shulker and furnace transactions, plus the contents of broken containers
- Explosions (TNT is credited to the player who lit it)
- Fire, and who lit it
- Buckets
- Pets, villagers and animals killed by players, and item frames or paintings broken
- Endermen, withers and other griefing mobs

### Commands
```
/gc                                   menu
/gc inspect                           toggle inspector
/gc lookup [u:player] [t:3d] [r:10] [a:break,take]   search (r:#global = everywhere)
/gc report [r:30] [t:7d]              who griefed this base?
/gc rollback u:player t:1d [r:20] [a:...]   undo changes
/gc restore  u:player t:1d [r:20]           undo a rollback
/gc trust <player> / untrust <player> trusted friends
/gc alerts | settings | purge <time> | reload
```
Time units are `s m h d w`, for example `t:1d12h`. Actions are `break place take put explode burn ignite kill`.
Non-player sources can be filtered as `u:#creeper`, `u:#tnt`, `u:#fire` and so on.

### Permissions
| Permission | Default | |
|---|---|---|
| `griefcheck.use` / `.inspect` / `.lookup` / `.teleport` | op | menu, inspector, lookups, teleport |
| `griefcheck.rollback` | op | rollback / restore |
| `griefcheck.alerts` | op | receive alerts |
| `griefcheck.admin` | op | settings, purge, reload |
| `griefcheck.trust` | everyone | trust friends |
| `griefcheck.alerts.exempt` | nobody | never triggers alerts |

Data is stored in `plugins/GriefCheck/griefcheck.db` (SQLite, written off the main thread).
Entries older than `retention-days` (default 60) are purged at startup.

---

## AdminGuard

Stops admins, or anyone who steals an admin account, from abusing powerful commands.

When a staff member runs a protected command, AdminGuard intercepts it before the server or any other plugin sees it.
The command's rule then decides what has to happen first:

| Mode | What has to happen |
|---|---|
| `LOG` | runs normally but is logged and staff are notified |
| `CONFIRM` | a confirm screen (stops accidents) |
| `PIN` | the player enters their personal PIN on a GUI keypad |
| `APPROVAL` | another staff member approves it (clickable `[✔ Approve] [✖ Deny]` in chat, or `/ag requests`) |
| `PIN_AND_APPROVAL` | both |
| `BLOCK` | can never be run in-game |

The approval modes are a two-person rule: one admin alone can't op themselves, change permissions or stop the server.
PINs protect against stolen accounts.

**Default rules** (edit them in `/ag rules` or `config.yml`):

| Mode | Commands |
|---|---|
| PIN + approval | `op`/`deop`, LuckPerms and other permission plugins, `stop`/`restart`/`reload`/PlugMan, `sudo` |
| Approval | `whitelist off`, any command using `@a`/`@e` |
| PIN | gamemode, give/item spawning, economy give/set |
| Confirm | fly/god/heal/vanish/effect, bans/kicks/mutes, gamerule/kill/clear/fill/summon, big WorldEdit commands |
| Log | invsee, teleporting others |

**Other protections:**
- `/execute ... run <command>` is unwrapped and checked, so protected commands can't be smuggled through it.
- Command blocks can't run commands that need a PIN or approval.
- 3 wrong PINs lock the account for 10 minutes and alert staff.
- Rate limit on protected commands per minute.
- Optional **OP whitelist**: anyone with OP who isn't listed is de-opped within 2 seconds.
- `owners:` list: only these players can change AdminGuard's rules and settings in-game, and changing them needs a PIN.
- Every change made in the settings GUI goes to the audit log.

**Audit log:** `/ag audit` is a GUI with filters. The full history is kept in `plugins/AdminGuard/audit.log`.
Events can also be shown in staff chat or posted to a **Discord webhook**. Set the URL in `/ag settings`; right-click the button to send a test.

**Sessions:** after a correct PIN, the player isn't asked again for `session-minutes` (default 5).
`/ag lock` ends the session early.

### First-time setup
1. Every staff member runs `/ag setpin`.
2. Put the server owner(s) in `owners:` in `plugins/AdminGuard/config.yml`.
3. Optional: turn on the OP whitelist in `/ag` → OP whitelist → "Add all current OPs" → enable.
4. Optional: add a Discord webhook in `/ag settings`.

### Commands
```
/ag                          menu
/ag setpin                   set / change your PIN
/ag lock                     end your verified session
/ag approve <id> | deny <id> [reason]
/ag requests | audit
/ag rules | settings | opwhitelist      (admins, PIN required)
/ag check <command>          which rule would this command hit?
/ag resetpin <player> | unlock <player>
/ag reload
```

### Permissions
| Permission | Default | |
|---|---|---|
| `adminguard.use` | op | menu, set PIN |
| `adminguard.approve` | op | approve or deny others' requests |
| `adminguard.audit` / `adminguard.notify` | op | view the audit log, see audit chat messages |
| `adminguard.admin` | op | rules, settings, reset PINs (also limited by `owners:`) |
| `adminguard.bypass` | **nobody** | skips every check. OPs do **not** get this by default |

PINs are stored as salted PBKDF2 hashes in `pins.yml`. To reset one by hand, delete that player's line.

**Limits:** console and RCON are trusted (their commands are logged, not blocked).
Someone with access to the server files can still edit the configs. AdminGuard protects against in-game abuse.
