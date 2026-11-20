package com.macroforge.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * "Stun web": (1) target has a shield up and you have an axe → axe hit to disable it, (2) put a cobweb on their feet
 * (optionally their head too), (3) switch back to the item you had. Runs one step per tick so the server accepts it.
 */
public final class Web {
    private Web() {}

    enum Stage { IDLE, AXE_HIT, KNOCK, WEB_WAIT, PLACE_HEAD, RESTORE }

    static Stage stage = Stage.IDLE;
    static Entity target;
    static int prevSlot = -1, webSlot = -1, axeSlot = -1, wait = 0, startTick;
    static boolean breakShield = true, head = false, look = true, switchBack = true, knock = true, needShield = false, waitAir = true;
    static double stunDelayMs = 550, webDelayMs = 50;
    static long stunAt, hitAt;
    static float prevYaw, prevPitch;
    static String result = "", lastTarget = "";
    static int placed = 0;

    public static boolean busy() { return stage != Stage.IDLE; }

    static void addState(JsonObject s) {
        s.addProperty("web_busy", busy());
        s.addProperty("web_result", result);
        s.addProperty("web_target", lastTarget);
        s.addProperty("web_count", Pvp.count(Minecraft.getInstance().player, "cobweb"));
    }

    static JsonObject command(JsonObject m, Minecraft mc, int tick) {
        JsonObject r = new JsonObject();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return fail(r, "not in a world");
        if (busy()) return fail(r, "already webbing");
        breakShield = !m.has("break_shield") || BridgeMod.bool(m, "break_shield");
        head = BridgeMod.bool(m, "head");
        look = !m.has("look") || BridgeMod.bool(m, "look");
        switchBack = !m.has("switch_back") || BridgeMod.bool(m, "switch_back");
        knock = !m.has("knock") || BridgeMod.bool(m, "knock");
        needShield = BridgeMod.bool(m, "need_shield");
        waitAir = !m.has("wait_air") || BridgeMod.bool(m, "wait_air");
        stunDelayMs = Math.max(0, Math.min(2000, BridgeMod.num(m, "stun_delay", 550)));
        webDelayMs = Math.max(0, Math.min(2000, BridgeMod.num(m, "web_delay", 50)));
        double range = BridgeMod.num(m, "range", 4.5);
        String who = BridgeMod.str(m, "target", "crosshair_or_nearest");
        Set<String> friends = new HashSet<>();
        for (String f : BridgeMod.str(m, "friends", "").split(",")) { String n = f.trim().toLowerCase(Locale.ROOT); if (!n.isEmpty()) friends.add(n); }
        boolean playersOnly = !m.has("players_only") || BridgeMod.bool(m, "players_only");

        Entity t = null;
        if (!who.equals("nearest") && mc.hitResult instanceof EntityHitResult eh && ok(p, eh.getEntity(), friends, playersOnly, range)) t = eh.getEntity();
        if (t == null && !who.equals("crosshair")) {
            double best = Double.MAX_VALUE;
            for (Entity en : mc.level.entitiesForRendering()) {
                if (!ok(p, en, friends, playersOnly, range)) continue;
                double d = en.distanceToSqr(p);
                if (d < best) { best = d; t = en; }
            }
        }
        if (t == null) return fail(r, "no target in range");

        webSlot = hotbar(p, "cobweb");
        if (webSlot < 0) return fail(r, "no cobweb in your hotbar");
        boolean shieldUp = t instanceof LivingEntity sl && sl.isUsingItem() && BridgeMod.itemId(sl.getUseItem()).endsWith("shield");
        if (needShield && !shieldUp) return fail(r, "their shield isn't up");
        if (!knock && place(mc, p, t, true) == null) return fail(r, whyNot);

        target = t; lastTarget = t.getName().getString(); placed = 0; hitAt = 0;
        prevSlot = BridgeMod.selectedSlotPublic(p); prevYaw = p.getYRot(); prevPitch = p.getXRot();
        startTick = tick; result = "";
        axeSlot = -1;
        if (breakShield && shieldUp && reach(p, t) <= 3.0) axeSlot = BridgeMod.itemId(p.getMainHandItem()).endsWith("_axe") ? prevSlot : hotbarAxe(p);
        stunAt = System.currentTimeMillis();
        if (axeSlot >= 0) { select(mc, axeSlot); stage = Stage.AXE_HIT; }          // 1. stun: axe hit disables their shield
        else if (knock) { select(mc, prevSlot); stage = Stage.KNOCK; stunAt = 0; } // no shield: straight to the knock-up hit
        else { toWeb(mc, p); }
        r.addProperty("ok", true);
        r.addProperty("target", lastTarget);
        r.addProperty("shield_break", axeSlot >= 0);
        return r;
    }

    static boolean ok(LocalPlayer p, Entity en, Set<String> friends, boolean playersOnly, double range) {
        if (en == p || !en.isAlive() || !(en instanceof LivingEntity) || en.isSpectator()) return false;
        if (playersOnly && !(en instanceof Player)) return false;
        if (!Aim.friendlyOk(p, en, friends, true, true)) return false;
        return Math.sqrt(en.getBoundingBox().distanceToSqr(p.getEyePosition())) <= range + 1.0;
    }

    static double reach(LocalPlayer p, Entity t) { return Math.sqrt(t.getBoundingBox().distanceToSqr(p.getEyePosition())); }

    static void toWeb(Minecraft mc, LocalPlayer p) {
        select(mc, webSlot);
        hitAt = hitAt == 0 ? System.currentTimeMillis() : hitAt;
        stage = Stage.WEB_WAIT;
        if (look) lookAtPlace(mc, p);
    }

    static JsonObject fail(JsonObject r, String why) { result = why; r.addProperty("ok", false); r.addProperty("error_text", why); return r; }

    static int hotbar(LocalPlayer p, String idEnd) {
        for (int i = 0; i < 9; i++) if (BridgeMod.itemId(p.getInventory().getItem(i)).endsWith(idEnd)) return i;
        return -1;
    }

    static int hotbarAxe(LocalPlayer p) { return hotbar(p, "_axe"); }

    static void select(Minecraft mc, int slot) {
        if (slot >= 0 && slot != BridgeMod.selectedSlotPublic(mc.player)) BridgeMod.pressKey(mc.options.keyHotbarSlots[slot]);   // applied at the start of next tick
    }

    // ---------------------------------------------------------------- where to put the web

    static String whyNot = "";

    /** Click that puts a cobweb into the target's feet (or head) block, or null (whyNot says why). */
    static BlockHitResult place(Minecraft mc, LocalPlayer p, Entity t, boolean feet) {
        if (!feet) return placeAt(mc, p, t.blockPosition().above(), false);
        // in the air (knocked up): web the block they're in if it can be placed, else the block they'll land in
        BlockPos b = t.blockPosition();
        String firstWhy = null;
        for (int i = 0; i < 4; i++) {
            BlockPos c = b.below(i);
            if (i > 0 && !mc.level.getBlockState(c).canBeReplaced()) break;   // hit the ground
            BlockHitResult h = placeAt(mc, p, c, true);
            if (h != null) return h;
            if (firstWhy == null) firstWhy = whyNot;
            if (whyNot.equals("already webbed")) return null;
        }
        whyNot = firstWhy == null ? whyNot : firstWhy;
        return null;
    }

    static BlockHitResult placeAt(Minecraft mc, LocalPlayer p, BlockPos spot, boolean feet) {
        BlockState here = mc.level.getBlockState(spot);
        if (BridgeMod.blockId(here).endsWith("cobweb")) { whyNot = "already webbed"; return feet ? null : null; }
        if (!here.canBeReplaced()) { whyNot = "no space at their " + (feet ? "feet" : "head"); return null; }
        if (p.getBoundingBox().intersects(new AABB(spot))) { whyNot = "too close – it would web you"; return null; }
        Vec3 eye = p.getEyePosition();
        double reach = 4.5;
        try { reach = p.blockInteractionRange(); } catch (Throwable ignored) {}
        BlockHitResult best = null; double bestD = Double.MAX_VALUE;
        Direction[] order = { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP };
        for (Direction d : order) {
            BlockPos n = spot.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) continue;   // need something to click against
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceTo(hit);
            if (dist > reach) continue;
            double score = dist + (d == Direction.DOWN ? -1.0 : 0);   // prefer the floor
            if (score < bestD) { bestD = score; best = new BlockHitResult(hit, face, n, false); }
        }
        if (best == null) whyNot = "nothing to place the web against within reach (target in the air?)";
        return best;
    }

    static BlockHitResult planned;

    static void lookAtPlace(Minecraft mc, LocalPlayer p) {
        if (target == null) return;
        planned = place(mc, p, target, true);
        if (planned == null) return;
        float[] yp = Aim.angles(p.getEyePosition(), planned.getLocation());
        Aim.setRot(p, p.getYRot() + BridgeMod.wrapDeg(yp[0] - p.getYRot()), yp[1]);
    }

    // ---------------------------------------------------------------- one step per tick (END of tick, after the player sent its rotation)

    static void tick(Minecraft mc, int tick) {
        if (stage == Stage.IDLE) return;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || tick - startTick > 20 + (int) ((stunDelayMs + webDelayMs) / 50) + 10) { finish(mc, result.isEmpty() ? "timed out" : result); return; }
        if (wait > 0) { wait--; return; }
        long now = System.currentTimeMillis();
        switch (stage) {
            case AXE_HIT: {   // 1. stun
                if (!BridgeMod.itemId(p.getMainHandItem()).endsWith("_axe")) return;   // slot switch not applied yet
                if (target.isAlive()) { mc.gameMode.attack(p, target); p.swing(InteractionHand.MAIN_HAND); }
                stunAt = now;
                if (knock) { select(mc, prevSlot); stage = Stage.KNOCK; }
                else toWeb(mc, p);
                return;
            }
            case KNOCK: {     // 2. after the set delay: quick hit to knock them up
                if (now - stunAt < stunDelayMs) return;
                if (prevSlot != BridgeMod.selectedSlotPublic(p) && axeSlot != prevSlot) return;   // weapon not back in hand yet
                if (!target.isAlive()) { finish(mc, "target gone"); return; }
                if (reach(p, target) > 3.0) { finish(mc, "target out of reach for the knock-up hit"); return; }
                mc.gameMode.attack(p, target); p.swing(InteractionHand.MAIN_HAND);
                hitAt = now;
                toWeb(mc, p);
                return;
            }
            case WEB_WAIT: {  // 3. web them while they're in the air
                if (!BridgeMod.itemId(p.getMainHandItem()).endsWith("cobweb")) return;
                if (now - hitAt < webDelayMs) { if (look) lookAtPlace(mc, p); return; }
                boolean airborne = !target.onGround();
                if (knock && waitAir && !airborne && now - hitAt < webDelayMs + 250) { if (look) lookAtPlace(mc, p); return; }   // give the knock-up a moment
                BlockHitResult h = place(mc, p, target, true);
                if (h == null) { if (now - hitAt < webDelayMs + 400) return; finish(mc, whyNot); return; }   // keep trying briefly
                if (look) { float[] yp = Aim.angles(p.getEyePosition(), h.getLocation()); Aim.setRot(p, p.getYRot() + BridgeMod.wrapDeg(yp[0] - p.getYRot()), yp[1]); }
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h);
                p.swing(InteractionHand.MAIN_HAND);
                placed++;
                stage = head ? Stage.PLACE_HEAD : Stage.RESTORE; wait = 1;
                return;
            }
            case PLACE_HEAD: {
                if (BridgeMod.itemId(p.getMainHandItem()).endsWith("cobweb")) {
                    BlockHitResult h = place(mc, p, target, false);
                    if (h != null) {
                        if (look) { float[] yp = Aim.angles(p.getEyePosition(), h.getLocation()); Aim.setRot(p, p.getYRot() + BridgeMod.wrapDeg(yp[0] - p.getYRot()), yp[1]); }
                        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h); p.swing(InteractionHand.MAIN_HAND); placed++;
                    }
                }
                stage = Stage.RESTORE; wait = 1;
                return;
            }
            case RESTORE: finish(mc, placed > 0 ? (axeSlot >= 0 ? "stunned + " : "") + (knock ? "knocked up + " : "") + "webbed " + lastTarget + (placed > 1 ? " (2 webs)" : "") : "not placed"); return;
            default: finish(mc, "");
        }
    }

    static void finish(Minecraft mc, String why) {
        LocalPlayer p = mc.player;
        if (p != null) {
            if (switchBack && prevSlot >= 0) select(mc, prevSlot);
            if (look) {   // turn back to where you were looking (smoothly, ~120 ms) – onto the target if it's still there
                JsonObject q = new JsonObject();
                if (target != null && target.isAlive()) { q.addProperty("target", "pos"); Vec3 c = target.getBoundingBox().getCenter(); q.addProperty("x", c.x); q.addProperty("y", c.y + 0.4); q.addProperty("z", c.z); }
                else { q.addProperty("target", "dir"); q.addProperty("yaw", prevYaw); q.addProperty("pitch", prevPitch); }
                q.addProperty("ms", 120);
                Aim.command(q, mc, 0);
            }
        }
        result = why;
        JsonObject e = BridgeMod.eventObjPublic("web", why);
        e.addProperty("ok", placed > 0);
        e.addProperty("target", lastTarget);
        BridgeMod.sendPublic(e);
        stage = Stage.IDLE; target = null; wait = 0; planned = null;
    }
}
