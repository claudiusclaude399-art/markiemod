package dev.markiemod.griefcheck;

import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.LogEntry;
import dev.markiemod.griefcheck.data.Query;
import dev.markiemod.griefcheck.util.MultiBlock;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Undoes (rollback) or re-applies (restore) logged changes, a few hundred per tick to avoid lag. */
public final class RollbackManager {

    private final GriefCheckPlugin plugin;
    private int running;

    public RollbackManager(GriefCheckPlugin plugin) {
        this.plugin = plugin;
    }

    public int running() {
        return running;
    }

    public void run(CommandSender sender, Query query, boolean rollback) {
        String verb = rollback ? "Rollback" : "Restore";
        plugin.send(sender, "<gray>" + verb + " started for <white>" + query.describe().replace("<", "") + "</white>...");
        plugin.database().forRollback(query, rollback).thenAccept(entries -> plugin.sync(() -> apply(sender, entries, rollback)))
                .exceptionally(t -> {
                    plugin.sync(() -> plugin.send(sender, "<red>" + verb + " failed: " + t.getMessage()));
                    return null;
                });
    }

    private void apply(CommandSender sender, List<LogEntry> entries, boolean rollback) {
        if (entries.isEmpty()) {
            plugin.msg(sender, "nothing-to-rollback");
            return;
        }
        running++;
        long start = System.currentTimeMillis();
        Iterator<LogEntry> it = entries.iterator();
        List<Long> done = new ArrayList<>();
        int perTick = plugin.settings().blocksPerTick;
        new BukkitRunnable() {
            int skipped;

            @Override
            public void run() {
                for (int i = 0; i < perTick && it.hasNext(); i++) {
                    LogEntry e = it.next();
                    try {
                        if (applyOne(e, rollback)) done.add(e.id);
                        else skipped++;
                    } catch (Exception ex) {
                        skipped++;
                    }
                }
                if (it.hasNext()) return;
                cancel();
                running--;
                plugin.database().markRolledBack(done, rollback);
                plugin.msg(sender, rollback ? "rollback-done" : "restore-done",
                        "count", String.valueOf(done.size()), "skipped", String.valueOf(skipped),
                        "seconds", String.format("%.1f", (System.currentTimeMillis() - start) / 1000.0));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static boolean isClear(Block b) {
        Material t = b.getType();
        return b.isEmpty() || b.isLiquid() || t == Material.FIRE || t == Material.SOUL_FIRE || b.isReplaceable();
    }

    private static BlockData parse(String data) {
        if (data == null) return null;
        try {
            return Bukkit.createBlockData(data);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void setBlock(Block b, BlockData data) {
        b.setBlockData(data, false);
        MultiBlock.Part other = MultiBlock.companion(b, data);
        if (other != null && isClear(other.block()) && other.block().getType() != data.getMaterial()) {
            other.block().setBlockData(other.data(), false);
        }
    }

    private static void clearBlock(Block b, BlockData replacement) {
        BlockData current = b.getBlockData();
        MultiBlock.Part other = MultiBlock.companion(b, current);
        b.setBlockData(replacement == null ? Material.AIR.createBlockData() : replacement, false);
        if (other != null && other.block().getType() == current.getMaterial()) {
            other.block().setType(Material.AIR, false);
        }
    }

    private static boolean matches(Block b, LogEntry e) {
        if (b.getType().name().equals(e.material)) return true;
        return "WATER".equals(e.material) && b.getBlockData() instanceof Waterlogged w && w.isWaterlogged();
    }

    /** @return true if the world was changed */
    private boolean applyOne(LogEntry e, boolean rollback) {
        World world = plugin.getServer().getWorld(e.world);
        if (world == null) return false;
        Block b = world.getBlockAt(e.x, e.y, e.z);
        switch (e.action) {
            case BREAK, EXPLODE, BURN -> {
                BlockData data = parse(e.data);
                if (data == null) return false; // e.g. item frames: nothing to put back
                if (rollback) {
                    if (!isClear(b)) return false;
                    setBlock(b, data);
                } else {
                    if (b.getType() != data.getMaterial()) return false;
                    clearBlock(b, null);
                }
                return true;
            }
            case PLACE -> {
                if (rollback) {
                    if (!matches(b, e)) return false;
                    if ("WATER".equals(e.material) && b.getBlockData() instanceof Waterlogged w && w.isWaterlogged()) {
                        w.setWaterlogged(false);
                        b.setBlockData(w, false);
                        return true;
                    }
                    clearBlock(b, parse(e.oldData));
                } else {
                    BlockData data = parse(e.data);
                    if (data == null || !isClear(b)) return false;
                    setBlock(b, data);
                }
                return true;
            }
            case TAKE, PUT -> {
                if (e.item == null || e.amount <= 0) return false;
                if (!(b.getState(false) instanceof Container c)) return false;
                Inventory inv = c.getInventory();
                ItemStack proto = ItemStack.deserializeBytes(e.item);
                List<ItemStack> stacks = new ArrayList<>();
                int left = e.amount;
                int max = Math.max(1, proto.getMaxStackSize());
                while (left > 0) {
                    int n = Math.min(left, max);
                    stacks.add(proto.asQuantity(n));
                    left -= n;
                }
                ItemStack[] arr = stacks.toArray(new ItemStack[0]);
                boolean giveBack = (e.action == Action.TAKE) == rollback;
                if (giveBack) {
                    inv.addItem(arr).values().forEach(rest -> world.dropItemNaturally(b.getLocation().add(0.5, 1, 0.5), rest));
                } else {
                    inv.removeItem(arr);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
