package dev.markiemod.adminguard.core;

import dev.markiemod.adminguard.AdminGuardPlugin;
import dev.markiemod.adminguard.gui.KeypadMenu;
import dev.markiemod.adminguard.util.TimeUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PIN checks. A correct PIN opens a short "trusted session" so staff aren't asked for every command;
 * too many wrong PINs lock the account out of protected commands and alert other staff.
 */
public final class Verifier {

    private final AdminGuardPlugin plugin;
    private final Map<UUID, Long> sessions = new HashMap<>();
    private final Map<UUID, Long> lockouts = new HashMap<>();
    private final Map<UUID, Integer> fails = new HashMap<>();

    public Verifier(AdminGuardPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isTrusted(Player p) {
        Long until = sessions.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public long sessionRemaining(Player p) {
        return Math.max(0, sessions.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis());
    }

    public void endSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public long lockRemaining(UUID uuid) {
        return Math.max(0, lockouts.getOrDefault(uuid, 0L) - System.currentTimeMillis());
    }

    public boolean isLocked(UUID uuid) {
        return lockRemaining(uuid) > 0;
    }

    public void unlock(UUID uuid) {
        lockouts.remove(uuid);
        fails.remove(uuid);
    }

    /**
     * Makes sure the player knows their PIN, then runs {@code ok}.
     *
     * @param force ask even if a trusted session is active (used when changing the PIN)
     */
    public void requirePin(Player p, String reason, boolean force, Runnable ok, Runnable cancel) {
        UUID id = p.getUniqueId();
        if (isLocked(id)) {
            plugin.msg(p, "locked-out", "time", TimeUtil.format(lockRemaining(id)));
            if (cancel != null) cancel.run();
            return;
        }
        if (!force && isTrusted(p)) {
            ok.run();
            return;
        }
        if (!plugin.pins().has(id)) {
            plugin.msg(p, "no-pin");
            if (cancel != null) cancel.run();
            return;
        }
        openKeypad(p, reason, ok, cancel, null);
    }

    private void openKeypad(Player p, String reason, Runnable ok, Runnable cancel, String error) {
        new KeypadMenu(plugin, p, "Enter your PIN", reason, error, code -> {
            UUID id = p.getUniqueId();
            if (plugin.pins().check(id, code)) {
                fails.remove(id);
                int minutes = plugin.getConfig().getInt("settings.session-minutes", 5);
                if (minutes > 0) sessions.put(id, System.currentTimeMillis() + minutes * 60_000L);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f);
                ok.run();
                return;
            }
            int max = Math.max(1, plugin.getConfig().getInt("settings.max-pin-attempts", 3));
            int n = fails.merge(id, 1, Integer::sum);
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.audit().log(p.getName(), AuditType.PIN_FAILED, null, "attempt " + n + "/" + max + " · " + reason);
            if (n >= max) {
                fails.remove(id);
                int lock = Math.max(1, plugin.getConfig().getInt("settings.lockout-minutes", 10));
                lockouts.put(id, System.currentTimeMillis() + lock * 60_000L);
                sessions.remove(id);
                plugin.audit().log(p.getName(), AuditType.LOCKED_OUT, null, max + " wrong PINs, locked for " + lock + "m");
                p.closeInventory();
                plugin.msg(p, "locked-out", "time", lock + "m");
                if (cancel != null) cancel.run();
            } else {
                openKeypad(p, reason, ok, cancel, "Wrong PIN · " + (max - n) + " tries left");
            }
        }, cancel).open();
    }

    /** Walks the player through setting (or changing) their PIN. */
    public void setPin(Player p) {
        Runnable choose = () -> new KeypadMenu(plugin, p, "Choose a new PIN", "Digits only", null, first -> {
            int min = Math.max(4, plugin.getConfig().getInt("settings.pin-min-length", 4));
            if (first.length() < min) {
                p.closeInventory();
                plugin.msg(p, "pin-too-short", "length", String.valueOf(min));
                return;
            }
            new KeypadMenu(plugin, p, "Repeat your new PIN", "Type it once more", null, second -> {
                p.closeInventory();
                if (!first.equals(second)) {
                    plugin.msg(p, "pin-mismatch");
                    return;
                }
                plugin.pins().set(p.getUniqueId(), first);
                plugin.audit().log(p.getName(), AuditType.PIN_CHANGED, null, "PIN set");
                plugin.msg(p, "pin-set");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            }, null).open();
        }, null).open();
        if (plugin.pins().has(p.getUniqueId())) requirePin(p, "Confirm your current PIN", true, choose, null);
        else choose.run();
    }
}
