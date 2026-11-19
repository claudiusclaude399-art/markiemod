package com.macroforge.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

import java.util.*;

/** Small in-game HUD: what MacroForge is doing, the current target, and PvP stats (CPS, combo, reach, charge). Configured from the app. */
public final class Hud {
    private Hud() {}

    static boolean enabled = true, showStatus = true, showTarget = true, showStats = true, glow = true;
    static String corner = "tl";
    static final ArrayDeque<Long> leftClicks = new ArrayDeque<>(), rightClicks = new ArrayDeque<>();
    static double lastReach = -1; static long lastReachAt;
    static Entity lastHit; static long lastHitAt;
    static final LinkedHashMap<String, Object[]> custom = new LinkedHashMap<>();   // key → {text, untilMillis}

    static void config(JsonObject m) {
        enabled = !m.has("enabled") || BridgeMod.bool(m, "enabled");
        showStatus = !m.has("status") || BridgeMod.bool(m, "status");
        showTarget = !m.has("target") || BridgeMod.bool(m, "target");
        showStats = !m.has("stats") || BridgeMod.bool(m, "stats");
        glow = !m.has("glow") || BridgeMod.bool(m, "glow");
        corner = BridgeMod.str(m, "corner", "tl");
    }

    static void text(JsonObject m) {
        String key = BridgeMod.str(m, "key", "macro"), t = BridgeMod.str(m, "text", "");
        double ms = BridgeMod.num(m, "ms", 3000);
        synchronized (custom) {
            if (t.isEmpty()) custom.remove(key);
            else custom.put(key, new Object[]{t, ms <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + (long) ms});
        }
    }

    /** Called by KeyMappingMixin for every press of a key (real or ours). */
    public static void onKey(net.minecraft.client.KeyMapping k, boolean down, boolean wasDown) {
        if (!down || wasDown) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        long now = System.currentTimeMillis();
        if (k == mc.options.keyAttack) synchronized (leftClicks) { leftClicks.add(now); }
        else if (k == mc.options.keyUse) synchronized (rightClicks) { rightClicks.add(now); }
    }

    static int cps(ArrayDeque<Long> q) {
        long cut = System.currentTimeMillis() - 1000;
        synchronized (q) { while (!q.isEmpty() && q.peekFirst() < cut) q.pollFirst(); return q.size(); }
    }

    static void onAttack(LocalPlayer p, Entity en) {
        lastReach = Math.sqrt(en.getBoundingBox().distanceToSqr(p.getEyePosition()));
        lastReachAt = System.currentTimeMillis();
        lastHit = en; lastHitAt = lastReachAt;
    }

    /** Who the HUD card and the glow are about: the aim target, else what auto-hit is hitting, else your last hit (3 s). */
    public static Entity target() {
        Aim.Req q = Aim.cur;
        if (q != null && q.found && q.target != null && q.target.isAlive()) return q.target;
        Minecraft mc = Minecraft.getInstance();
        if (BridgeMod.assist != null && mc.hitResult instanceof EntityHitResult eh && eh.getEntity() instanceof LivingEntity) return eh.getEntity();
        if (lastHit != null && lastHit.isAlive() && System.currentTimeMillis() - lastHitAt < 3000) return lastHit;
        return null;
    }

    public static boolean shouldGlow(Entity e) {
        return enabled && glow && e != null && e == target();
    }

    // ------------------------------------------------------------------ drawing

    static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFAAAAAA, AQUA = 0xFF55FFFF, GREEN = 0xFF55FF55, RED = 0xFFFF5555, YELLOW = 0xFFFFFF55, BG = 0x90000000;

    public static void render(GuiGraphics g, DeltaTracker dt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!enabled || p == null || mc.options.hideGui) return;
        Font f = mc.font;
        List<Object[]> lines = new ArrayList<>();   // {text, color} or {"#bar", fraction, color}

        if (showStatus) {
            StringBuilder sb = new StringBuilder();
            if (BridgeMod.assist != null) sb.append(BridgeMod.assist.needAttackHeld ? "AUTO-HIT (hold)  " : "AUTO-HIT  ");
            if (BridgeMod.assist != null && BridgeMod.assist.wtapTicks > 0) sb.append("W-TAP  ");
            Aim.Req q = Aim.cur;
            if (q != null) sb.append(q.locked ? "AIM [locked]" : q.found ? "AIM" : "AIM (no target)").append("  ");
            if (sb.length() > 0) lines.add(new Object[]{"MacroForge  " + sb.toString().trim(), AQUA});
        }

        if (showTarget) {
            Entity t = target();
            if (t != null) {
                double d = Math.sqrt(t.getBoundingBox().distanceToSqr(p.getEyePosition()));
                String name = t.getName().getString();
                String extra = String.format(Locale.ROOT, "  %.1fm", d);
                if (t instanceof LivingEntity le) {
                    boolean shield = le.isUsingItem() && BridgeMod.itemId(le.getUseItem()).endsWith("shield");
                    lines.add(new Object[]{name + extra + (shield ? "  SHIELD" : "") + (le.hurtTime > 0 ? "  hit" : ""), shield ? RED : WHITE});
                    float frac = le.getMaxHealth() <= 0 ? 0 : Math.min(1f, (le.getHealth() + le.getAbsorptionAmount()) / le.getMaxHealth());
                    int col = frac > 0.6f ? GREEN : frac > 0.3f ? YELLOW : RED;
                    lines.add(new Object[]{"#bar", frac, col, String.format(Locale.ROOT, "%.1f / %.0f HP%s", le.getHealth() + le.getAbsorptionAmount(), le.getMaxHealth(),
                            t instanceof Player ? "  armor " + le.getArmorValue() : "")});
                } else lines.add(new Object[]{name + extra, WHITE});
            }
        }

        if (showStats) {
            int l = cps(leftClicks), r = cps(rightClicks);
            String reach = lastReach >= 0 && System.currentTimeMillis() - lastReachAt < 10000 ? String.format(Locale.ROOT, "%.2f", lastReach) : "-";
            lines.add(new Object[]{"CPS " + l + " | " + r + "   Combo " + BridgeMod.combo() + "   Reach " + reach, GRAY});
            float ch = p.getAttackStrengthScale(0.5f);
            lines.add(new Object[]{"#bar", ch, ch >= 0.95f ? GREEN : ch >= 0.6f ? YELLOW : RED, "Charge " + Math.round(ch * 100) + "%"});
        }

        long now = System.currentTimeMillis();
        synchronized (custom) {
            custom.values().removeIf(v -> (Long) v[1] < now);
            for (Object[] v : custom.values()) lines.add(new Object[]{v[0], WHITE});
        }
        if (lines.isEmpty()) return;

        int lh = f.lineHeight + 2, barW = 90, w = 0;
        for (Object[] ln : lines) w = Math.max(w, "#bar".equals(ln[0]) ? barW + 4 + f.width((String) ln[3]) : f.width((String) ln[0]));
        int h = lines.size() * lh;
        int x = corner.endsWith("r") ? g.guiWidth() - w - 6 : 6;
        int y = corner.startsWith("b") ? g.guiHeight() - h - 40 : 6;
        g.fill(x - 3, y - 3, x + w + 3, y + h + 1, BG);
        for (Object[] ln : lines) {
            if ("#bar".equals(ln[0])) {
                float frac = (Float) ln[1];
                g.fill(x, y + 2, x + barW, y + lh - 3, 0xFF333333);
                g.fill(x, y + 2, x + Math.max(1, Math.round(barW * Math.max(0, Math.min(1, frac)))), y + lh - 3, (Integer) ln[2]);
                g.drawString(f, (String) ln[3], x + barW + 4, y + 1, WHITE, true);
            } else g.drawString(f, (String) ln[0], x, y + 1, (Integer) ln[1], true);
            y += lh;
        }
    }
}
