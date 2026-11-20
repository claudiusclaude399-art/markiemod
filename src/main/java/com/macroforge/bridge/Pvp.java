package com.macroforge.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

import java.util.*;

/** PvP helpers that run inside the game every tick: auto-totem, auto-eat (gapples), auto-armor, totem pop counter. */
public final class Pvp {
    private Pvp() {}

    // config (from the app, "pvp" command; switches itself off 2 s after the app stops refreshing)
    static boolean on, totem = true, eat = true, armor = true;
    static int until, totemDelay = 2, totemBelow = 0;          // totemBelow: only keep a totem in offhand below this HP (0 = always)
    static double eatBelow = 10;
    static final List<String> eatItems = new ArrayList<>(List.of("enchanted_golden_apple", "golden_apple"));
    static boolean eatAbsorbed = true;                          // don't eat while you still have absorption hearts

    // runtime
    static int totemAt = -1, lastArmorTick = -100;
    static int eatSlot = -1, eatPrev = -1, eatStart = -1, eatCount = 0;
    static final Map<String, Integer> pops = new LinkedHashMap<>();   // player name → totem pops (this world)
    static String lastPopName = ""; static long lastPopAt;

    static void config(JsonObject m, int tick) {
        on = !m.has("on") || BridgeMod.bool(m, "on");
        totem = !m.has("totem") || BridgeMod.bool(m, "totem");
        eat = !m.has("eat") || BridgeMod.bool(m, "eat");
        armor = !m.has("armor") || BridgeMod.bool(m, "armor");
        totemDelay = (int) Math.max(0, Math.min(40, BridgeMod.num(m, "totem_delay", 2)));
        totemBelow = (int) Math.max(0, BridgeMod.num(m, "totem_below", 0));
        eatBelow = Math.max(0, BridgeMod.num(m, "eat_below", 10));
        eatAbsorbed = !m.has("eat_absorbed") || BridgeMod.bool(m, "eat_absorbed");
        String items = BridgeMod.str(m, "eat_items", "");
        if (!items.isEmpty()) {
            eatItems.clear();
            for (String s : items.split(",")) { String t = s.trim().toLowerCase(Locale.ROOT); if (t.contains(":")) t = t.substring(t.indexOf(':') + 1); if (!t.isEmpty()) eatItems.add(t); }
        }
        until = tick + (int) Math.max(5, Math.min(200, BridgeMod.num(m, "ttl", 40)));
        if (!on) stopEating(Minecraft.getInstance());
    }

    static void reset() { pops.clear(); totemAt = -1; eatSlot = -1; }

    // ---------------------------------------------------------------- per tick

    static void tick(Minecraft mc, int tick) {
        LocalPlayer p = mc.player;
        if (on && tick > until) { on = false; stopEating(mc); }
        if (!on || p == null || mc.gameMode == null || !p.isAlive()) { if (eatSlot >= 0) stopEating(mc); return; }
        if (eatSlot >= 0) { continueEating(mc, p, tick); return; }   // busy eating: nothing else
        if (mc.screen != null) return;                              // inventory clicks only with no menu open
        if (totem) autoTotem(mc, p, tick);
        if (eat) autoEat(mc, p, tick);
        if (armor && tick - lastArmorTick >= 4) { lastArmorTick = tick; autoArmor(mc, p); }
    }

    static float hp(LocalPlayer p) { return p.getHealth() + p.getAbsorptionAmount(); }

    static void autoTotem(Minecraft mc, LocalPlayer p, int tick) {
        if (BridgeMod.itemId(p.getOffhandItem()).endsWith("totem_of_undying")) { totemAt = -1; return; }
        if (totemBelow > 0 && hp(p) > totemBelow) return;
        if (totemAt < 0) { totemAt = tick + totemDelay; return; }      // small delay (looks human, lets the pop finish)
        if (tick < totemAt) return;
        int inv = find(p, "totem_of_undying", true);
        if (inv < 0) return;
        swapOffhand(mc, p, inv);
        totemAt = -1;
    }

    static void autoEat(Minecraft mc, LocalPlayer p, int tick) {
        if (hp(p) > eatBelow || p.isUsingItem()) return;
        if (eatAbsorbed && p.getAbsorptionAmount() > 0) return;
        if (BridgeMod.assist != null && mc.options.keyAttack.isDown() && hp(p) > eatBelow * 0.6) return;   // mid-fight and not critical: keep swinging
        for (String want : eatItems) {
            for (int i = 0; i < 9; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty() || !BridgeMod.itemId(st).endsWith(want)) continue;
                if (p.getCooldowns().isOnCooldown(st)) continue;
                eatPrev = BridgeMod.selectedSlotPublic(p);
                eatSlot = i; eatStart = tick; eatCount = st.getCount();
                if (i != eatPrev) BridgeMod.pressKey(mc.options.keyHotbarSlots[i]);
                BridgeMod.holdKey(mc.options.keyUse, true);
                return;
            }
        }
    }

    static void continueEating(Minecraft mc, LocalPlayer p, int tick) {
        ItemStack st = p.getInventory().getItem(eatSlot);
        boolean eaten = st.isEmpty() || st.getCount() < eatCount || !eatItems.stream().anyMatch(w -> BridgeMod.itemId(st).endsWith(w));
        boolean gaveUp = tick - eatStart > 50 || (tick - eatStart > 6 && !p.isUsingItem()) || mc.screen != null;
        if (eaten || gaveUp) stopEating(mc);
    }

    static void stopEating(Minecraft mc) {
        if (eatSlot < 0) return;
        BridgeMod.holdKey(mc.options.keyUse, false);
        if (eatPrev >= 0 && eatPrev != eatSlot) BridgeMod.pressKey(mc.options.keyHotbarSlots[eatPrev]);
        eatSlot = -1; eatPrev = -1;
    }

    static final EquipmentSlot[] ARMOR = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    static void autoArmor(Minecraft mc, LocalPlayer p) {
        for (EquipmentSlot es : ARMOR) {
            if (!p.getItemBySlot(es).isEmpty()) continue;
            int best = -1, bestScore = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (st.isEmpty()) continue;
                Equippable eq = st.get(DataComponents.EQUIPPABLE);
                if (eq == null || eq.slot() != es) continue;
                String id = BridgeMod.itemId(st);
                if (id.endsWith("elytra") || id.endsWith("carved_pumpkin") || id.contains("head") || id.contains("skull")) continue;
                int score = tier(id);
                if (score > bestScore) { bestScore = score; best = i; }
            }
            if (best >= 0) {
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(best), 0, ClickType.QUICK_MOVE, p);   // shift-click = put it on
                return;   // one piece per call
            }
        }
    }

    static int tier(String id) {
        if (id.contains("netherite")) return 6; if (id.contains("diamond")) return 5; if (id.contains("iron")) return 4;
        if (id.contains("chainmail")) return 3; if (id.contains("turtle")) return 3; if (id.contains("gold")) return 2; if (id.contains("leather")) return 1; return 0;
    }

    /** Inventory index (0-35) of an item, hotbar last when preferMain. */
    static int find(LocalPlayer p, String idEnd, boolean preferMain) {
        int hot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty() || !BridgeMod.itemId(st).endsWith(idEnd)) continue;
            if (i >= 9 || !preferMain) return i;
            if (hot < 0) hot = i;
        }
        return hot;
    }

    static int menuSlot(int inv) { return inv < 9 ? 36 + inv : inv; }   // player inventory menu numbering

    static void swapOffhand(Minecraft mc, LocalPlayer p, int inv) {
        mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId, menuSlot(inv), 40, ClickType.SWAP, p);   // button 40 = offhand
    }

    // ---------------------------------------------------------------- totem pops (from LivingEntityMixin)

    public static void onEntityEvent(Entity e, byte id) {
        if (id != 35 || !(e instanceof LivingEntity)) return;
        Minecraft mc = Minecraft.getInstance();
        if (e == mc.player) return;   // your own pop is reported elsewhere ("totem_pop")
        String name = e.getName().getString();
        int n = pops.merge(name, 1, Integer::sum);
        lastPopName = name; lastPopAt = System.currentTimeMillis();
        JsonObject ev = BridgeMod.eventObjPublic("enemy_pop", name + " popped a totem (" + n + ")");
        ev.addProperty("name", name);
        ev.addProperty("count", n);
        ev.addProperty("is_player", e instanceof Player);
        BridgeMod.sendPublic(ev);
    }

    static int popsOf(Entity e) { Integer n = pops.get(e.getName().getString()); return n == null ? 0 : n; }

    // ---------------------------------------------------------------- HUD helpers

    /** Lowest durability % of worn armor (-1 = none / unbreakable). */
    static int armorDurability(LivingEntity le) {
        int min = -1;
        for (EquipmentSlot es : ARMOR) {
            ItemStack st = le.getItemBySlot(es);
            if (st.isEmpty() || !st.isDamageableItem() || st.getMaxDamage() <= 0) continue;
            int pct = Math.round(100f * (st.getMaxDamage() - st.getDamageValue()) / st.getMaxDamage());
            if (min < 0 || pct < min) min = pct;
        }
        return min;
    }

    static int count(LocalPlayer p, String idEnd) {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) { ItemStack st = p.getInventory().getItem(i); if (!st.isEmpty() && BridgeMod.itemId(st).endsWith(idEnd)) n += st.getCount(); }
        return n;
    }

    static float pearlCooldown(LocalPlayer p) {
        int i = find(p, "ender_pearl", false);
        if (i < 0) return 0;
        return p.getCooldowns().getCooldownPercent(p.getInventory().getItem(i), 0f);
    }
}
