package dev.markiemod.griefcheck.listener;

import dev.markiemod.griefcheck.GriefCheckPlugin;
import dev.markiemod.griefcheck.Settings;
import dev.markiemod.griefcheck.data.Action;
import dev.markiemod.griefcheck.data.LogEntry;
import dev.markiemod.griefcheck.util.MultiBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Records every griefing-relevant change into the database queue. */
public final class LogListener implements Listener {

    /** What was in a container when a player opened it. Compared on close. */
    private record Session(Location location, Inventory inventory, ItemStack[] before) {
    }

    private final GriefCheckPlugin plugin;
    private final Map<UUID, Session> openContainers = new HashMap<>();

    public LogListener(GriefCheckPlugin plugin) {
        this.plugin = plugin;
    }

    private Settings s() {
        return plugin.settings();
    }

    private boolean skip(Location loc) {
        return s().excludedWorlds.contains(loc.getWorld().getName());
    }

    private void log(String actor, UUID uuid, Location loc, Action action, String material, String data, String oldData,
                     int amount, byte[] item) {
        plugin.database().log(new LogEntry(System.currentTimeMillis(), actor, uuid, loc.getWorld().getName(),
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), action, material, data, oldData, amount, item));
    }

    private void logBlock(String actor, UUID uuid, Block block, Action action, BlockData data, BlockData old) {
        log(actor, uuid, block.getLocation(), action, data.getMaterial().name(), data.getAsString(),
                old == null || old.getMaterial().isAir() ? null : old.getAsString(), 0, null);
    }

    /** Logs a broken block plus the other half of doors, beds, tall plants. */
    private void logRemoved(String actor, UUID uuid, Block block, Action action) {
        BlockData data = block.getBlockData();
        if (data.getMaterial().isAir()) return;
        logBlock(actor, uuid, block, action, data, null);
        MultiBlock.Part other = MultiBlock.companion(block, data);
        if (other != null && other.block().getType() == data.getMaterial()) {
            logBlock(actor, uuid, other.block(), action, other.block().getBlockData(), null);
        }
    }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!s().logBreak || skip(e.getBlock().getLocation())) return;
        Player p = e.getPlayer();
        Block b = e.getBlock();
        // Items in a broken container drop for whoever picks them up; record them as taken.
        // Logged BEFORE the break so a rollback (newest first) restores the chest before refilling it.
        if (s().logContainers && b.getState(false) instanceof Container c && !(c.getInventory().getHolder(false) instanceof DoubleChest)) {
            for (ItemStack item : c.getInventory().getContents()) {
                if (item == null || item.isEmpty()) continue;
                log(p.getName(), p.getUniqueId(), b.getLocation(), Action.TAKE, item.getType().name(), null, null,
                        item.getAmount(), item.asOne().serializeAsBytes());
            }
        }
        logRemoved(p.getName(), p.getUniqueId(), b, Action.BREAK);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!s().logPlace || skip(e.getBlock().getLocation())) return;
        Player p = e.getPlayer();
        if (e instanceof BlockMultiPlaceEvent multi) {
            for (BlockState replaced : multi.getReplacedBlockStates()) {
                Block b = replaced.getBlock();
                logBlock(p.getName(), p.getUniqueId(), b, Action.PLACE, b.getBlockData(), replaced.getBlockData());
            }
            return;
        }
        Block b = e.getBlockPlaced();
        logBlock(p.getName(), p.getUniqueId(), b, Action.PLACE, b.getBlockData(), e.getBlockReplacedState().getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (!s().logBuckets || skip(e.getBlock().getLocation())) return;
        Material liquid = switch (e.getBucket()) {
            case LAVA_BUCKET -> Material.LAVA;
            case POWDER_SNOW_BUCKET -> Material.POWDER_SNOW;
            default -> Material.WATER;
        };
        Block b = e.getBlock();
        Player p = e.getPlayer();
        BlockData old = b.getBlockData();
        log(p.getName(), p.getUniqueId(), b.getLocation(), Action.PLACE, liquid.name(), liquid.createBlockData().getAsString(),
                old.getMaterial().isAir() ? null : old.getAsString(), 0, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (!s().logBuckets || skip(e.getBlock().getLocation())) return;
        Block b = e.getBlock();
        if (b.getType().isAir()) return;
        logBlock(e.getPlayer().getName(), e.getPlayer().getUniqueId(), b, Action.BREAK, b.getBlockData(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!s().logExplosions || e.blockList().isEmpty() || skip(e.getLocation())) return;
        Entity entity = e.getEntity();
        String actor = "#" + entity.getType().name().toLowerCase(Locale.ROOT);
        UUID uuid = null;
        if (entity instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) {
            actor = p.getName();
            uuid = p.getUniqueId();
        }
        for (Block b : e.blockList()) {
            if (!b.getType().isAir()) logBlock(actor, uuid, b, Action.EXPLODE, b.getBlockData(), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!s().logExplosions || e.blockList().isEmpty() || skip(e.getBlock().getLocation())) return;
        String actor = "#" + e.getExplodedBlockState().getType().name().toLowerCase(Locale.ROOT);
        for (Block b : e.blockList()) {
            if (!b.getType().isAir()) logBlock(actor, null, b, Action.EXPLODE, b.getBlockData(), null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (!s().logFire || skip(e.getBlock().getLocation())) return;
        logBlock("#fire", null, e.getBlock(), Action.BURN, e.getBlock().getBlockData(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!s().logFire || e.getPlayer() == null || skip(e.getBlock().getLocation())) return;
        Player p = e.getPlayer();
        log(p.getName(), p.getUniqueId(), e.getBlock().getLocation(), Action.IGNITE, Material.FIRE.name(), null, null, 0, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Entity entity = e.getEntity();
        if (!s().logMobChanges || entity instanceof Player || entity instanceof FallingBlock) return;
        if (!s().mobTypes.contains(entity.getType()) || skip(e.getBlock().getLocation())) return;
        String actor = "#" + entity.getType().name().toLowerCase(Locale.ROOT);
        Block b = e.getBlock();
        BlockData before = b.getBlockData();
        BlockData after = e.getBlockData();
        if (after.getMaterial().isAir()) {
            if (!before.getMaterial().isAir()) logBlock(actor, null, b, Action.BREAK, before, null);
        } else {
            logBlock(actor, null, b, Action.PLACE, after, before);
        }
    }

    // ------------------------------------------------------------------ entities

    private static Player playerOf(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) return p;
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(EntityDeathEvent e) {
        if (!s().logKills) return;
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || dead instanceof Player || dead instanceof Monster || skip(dead.getLocation())) return;
        String detail = null;
        if (dead instanceof Tameable t && t.getOwner() != null) detail = "owner: " + t.getOwner().getName();
        else if (dead.customName() != null) detail = "named";
        log(killer.getName(), killer.getUniqueId(), dead.getLocation(), Action.KILL, dead.getType().name(), detail, null, 0, null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        if (!s().logKills || e.getRemover() == null) return;
        Player p = playerOf(e.getRemover());
        if (p == null || skip(e.getEntity().getLocation())) return;
        log(p.getName(), p.getUniqueId(), e.getEntity().getLocation(), Action.KILL, e.getEntity().getType().name(), null, null, 0, null);
    }

    // ------------------------------------------------------------------ containers

    /** Location used for a container: the left half for double chests, so both halves share one history. */
    public static Location containerLocation(InventoryHolder holder) {
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof BlockState left) return left.getLocation();
            return null;
        }
        if (holder instanceof BlockState state) return state.getLocation();
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (!s().logContainers || !(e.getPlayer() instanceof Player p)) return;
        Inventory inv = e.getInventory();
        InventoryHolder holder = inv.getHolder(false);
        if (!(holder instanceof Container) && !(holder instanceof DoubleChest)) return;
        Location loc = containerLocation(holder);
        if (loc == null || skip(loc)) return;
        openContainers.put(p.getUniqueId(), new Session(loc, inv, copy(inv.getContents())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent e) {
        Session session = openContainers.remove(e.getPlayer().getUniqueId());
        if (session != null && e.getPlayer() instanceof Player p) diff(p, session);
    }

    public void flushOpenContainers() {
        for (Map.Entry<UUID, Session> entry : openContainers.entrySet()) {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null) diff(p, entry.getValue());
        }
        openContainers.clear();
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) out[i] = items[i] == null ? null : items[i].clone();
        return out;
    }

    /** Groups similar items and counts them. */
    private static List<Map.Entry<ItemStack, Integer>> count(ItemStack[] items) {
        List<Map.Entry<ItemStack, Integer>> out = new ArrayList<>();
        outer:
        for (ItemStack item : items) {
            if (item == null || item.isEmpty()) continue;
            for (Map.Entry<ItemStack, Integer> e : out) {
                if (e.getKey().isSimilar(item)) {
                    e.setValue(e.getValue() + item.getAmount());
                    continue outer;
                }
            }
            out.add(new java.util.AbstractMap.SimpleEntry<>(item.asOne(), item.getAmount()));
        }
        return out;
    }

    private static int find(List<Map.Entry<ItemStack, Integer>> list, ItemStack key) {
        for (Map.Entry<ItemStack, Integer> e : list) if (e.getKey().isSimilar(key)) return e.getValue();
        return 0;
    }

    private void diff(Player p, Session session) {
        List<Map.Entry<ItemStack, Integer>> before = count(session.before());
        List<Map.Entry<ItemStack, Integer>> after = count(session.inventory().getContents());
        for (Map.Entry<ItemStack, Integer> e : before) {
            int delta = find(after, e.getKey()) - e.getValue();
            if (delta < 0) record(p, session.location(), Action.TAKE, e.getKey(), -delta);
        }
        for (Map.Entry<ItemStack, Integer> e : after) {
            int delta = e.getValue() - find(before, e.getKey());
            if (delta > 0) record(p, session.location(), Action.PUT, e.getKey(), delta);
        }
    }

    private void record(Player p, Location loc, Action action, ItemStack item, int amount) {
        log(p.getName(), p.getUniqueId(), loc, action, item.getType().name(), null, null, amount, item.serializeAsBytes());
    }
}
