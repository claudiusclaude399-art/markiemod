package dev.markiemod.griefcheck;

import dev.markiemod.griefcheck.util.TimeUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Cached copy of config.yml so event handlers don't hit the config map on every block change. */
public final class Settings {
    public boolean logBreak, logPlace, logContainers, logExplosions, logFire, logBuckets, logKills, logMobChanges;
    public Set<String> excludedWorlds = new HashSet<>();
    public Set<EntityType> mobTypes = EnumSet.noneOf(EntityType.class);

    public boolean alertsEnabled, alertContainers, alertSound;
    public int alertThreshold, alertWindowSeconds, alertCooldownSeconds;

    public int defaultRadius, reportRadius;
    public long defaultTime, reportTime;
    public int inspectorLines;
    public int maxRollbackRadius, blocksPerTick;
    public int retentionDays;

    public void load(FileConfiguration c) {
        logBreak = c.getBoolean("logging.block-break", true);
        logPlace = c.getBoolean("logging.block-place", true);
        logContainers = c.getBoolean("logging.containers", true);
        logExplosions = c.getBoolean("logging.explosions", true);
        logFire = c.getBoolean("logging.fire", true);
        logBuckets = c.getBoolean("logging.buckets", true);
        logKills = c.getBoolean("logging.entity-kills", true);
        logMobChanges = c.getBoolean("logging.mob-block-changes", true);
        excludedWorlds = new HashSet<>(c.getStringList("logging.excluded-worlds"));
        mobTypes = EnumSet.noneOf(EntityType.class);
        for (String s : c.getStringList("logging.mob-types")) {
            try {
                mobTypes.add(EntityType.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }

        alertsEnabled = c.getBoolean("alerts.enabled", true);
        alertThreshold = Math.max(1, c.getInt("alerts.threshold", 15));
        alertWindowSeconds = Math.max(5, c.getInt("alerts.window-seconds", 60));
        alertCooldownSeconds = Math.max(0, c.getInt("alerts.cooldown-seconds", 120));
        alertContainers = c.getBoolean("alerts.count-container-takes", true);
        alertSound = c.getBoolean("alerts.sound", true);

        defaultRadius = Math.max(1, c.getInt("lookup.default-radius", 10));
        defaultTime = parseOr(c.getString("lookup.default-time"), 3 * 86_400_000L);
        reportRadius = Math.max(1, c.getInt("report.default-radius", 30));
        reportTime = parseOr(c.getString("report.default-time"), 7 * 86_400_000L);
        inspectorLines = Math.max(3, c.getInt("inspector.lines-per-page", 8));

        maxRollbackRadius = Math.max(1, c.getInt("rollback.max-radius", 100));
        blocksPerTick = Math.max(10, c.getInt("rollback.blocks-per-tick", 400));
        retentionDays = Math.max(0, c.getInt("retention-days", 60));
    }

    private static long parseOr(String s, long def) {
        long v = TimeUtil.parse(s);
        return v > 0 ? v : def;
    }
}
