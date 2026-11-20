package com.macroforge.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Web drain: an enemy is stuck in a cobweb and places water to wash it away → put a cobweb into the new water
 * source right away (a block placed into water replaces it), before it flows (~5 ticks) and breaks their web.
 * Runs at the START of every tick, right after the game applied the block updates from the server.
 */
public final class Drain {
    private Drain() {}

    static boolean on, look = true, rewebFeet = true, playersOnly = true, alsoLava = false, onlyWebbed = true;
    static int until;
    static double range = 6;
    static final Set<String> friends = new HashSet<>();

    static Set<Long> lastWater = new HashSet<>(), lastScanned = new HashSet<>();
    static int drained = 0, rewebbed = 0, cooldownUntil = -1;
    static String lastResult = "";
    static final Map<Integer, Integer> webbedUntil = new HashMap<>();   // entity id → tick until which we treat them as webbed (web just washed away)

    static void config(JsonObject m, int tick) {
        on = !m.has("on") || BridgeMod.bool(m, "on");
        look = !m.has("look") || BridgeMod.bool(m, "look");
        rewebFeet = !m.has("reweb") || BridgeMod.bool(m, "reweb");
        playersOnly = !m.has("players_only") || BridgeMod.bool(m, "players_only");
        alsoLava = BridgeMod.bool(m, "lava");
        onlyWebbed = !m.has("only_webbed") || BridgeMod.bool(m, "only_webbed");
        range = Math.max(2, Math.min(8, BridgeMod.num(m, "range", 6)));
        friends.clear();
        for (String f : BridgeMod.str(m, "friends", "").split(",")) { String n = f.trim().toLowerCase(Locale.ROOT); if (!n.isEmpty()) friends.add(n); }
        until = tick + (int) Math.max(5, Math.min(200, BridgeMod.num(m, "ttl", 40)));
        if (!on) { lastWater.clear(); lastScanned.clear(); }
    }

    static void addState(JsonObject s) {
        s.addProperty("drain_on", on);
        s.addProperty("drain_count", drained);
        s.addProperty("drain_reweb_count", rewebbed);
        s.addProperty("drain_result", lastResult);
    }

    static boolean webbed(Minecraft mc, Entity e) {
        AABB bb = e.getBoundingBox();
        for (BlockPos b : BlockPos.betweenClosed(BlockPos.containing(bb.minX, bb.minY, bb.minZ), BlockPos.containing(bb.maxX - 1e-4, bb.maxY - 1e-4, bb.maxZ - 1e-4)))
            if (BridgeMod.blockId(mc.level.getBlockState(b)).endsWith("cobweb")) return true;
        return false;
    }

    static boolean liquidSource(BlockState st) {
        FluidState f = st.getFluidState();
        if (f.isEmpty() || !f.isSource() || !st.canBeReplaced()) return false;   // waterlogged slabs etc. can't be replaced: skip
        return f.is(FluidTags.WATER) || (alsoLava && f.is(FluidTags.LAVA));
    }

    /** START of tick: block updates from the server have just been applied. */
    static void tick(Minecraft mc, int tick) {
        LocalPlayer p = mc.player;
        if (on && tick > until) on = false;
        if (!on || p == null || mc.level == null || mc.gameMode == null || mc.screen != null || !p.isAlive()) { lastWater.clear(); lastScanned.clear(); return; }
        if (Web.busy()) return;

        Set<Long> water = new HashSet<>(), scanned = new HashSet<>();
        List<long[]> fresh = new ArrayList<>();   // {pos, entityId}
        for (Entity en : mc.level.entitiesForRendering()) {
            if (en == p || !en.isAlive() || en.isSpectator()) continue;
            if (playersOnly && !(en instanceof Player)) continue;
            if (!(en instanceof net.minecraft.world.entity.LivingEntity)) continue;
            if (!Aim.friendlyOk(p, en, friends, true, true)) continue;
            if (en.distanceTo(p) > range + 2) continue;
            boolean inWeb = webbed(mc, en);
            if (inWeb) webbedUntil.put(en.getId(), tick + 10);
            Integer wu = webbedUntil.get(en.getId());
            boolean care = !onlyWebbed || inWeb || (wu != null && wu >= tick);
            if (!care) continue;
            BlockPos f = en.blockPosition();
            for (BlockPos b : BlockPos.betweenClosed(f.offset(-2, -1, -2), f.offset(2, 2, 2))) {
                long k = b.asLong();
                scanned.add(k);
                if (!liquidSource(mc.level.getBlockState(b))) continue;
                water.add(k);
                if (!lastWater.contains(k) && lastScanned.contains(k)) fresh.add(new long[]{k, en.getId()});   // appeared since last tick
            }
            // their web got washed away anyway (e.g. we were too slow): put it back
            if (rewebFeet && !inWeb && wu != null && wu >= tick && tick >= cooldownUntil) {
                BlockPos feet = en.blockPosition();
                if (mc.level.getBlockState(feet).canBeReplaced() && !BridgeMod.blockId(mc.level.getBlockState(feet)).endsWith("cobweb")) {
                    if (placeWeb(mc, p, feet)) { rewebbed++; lastResult = "re-webbed " + en.getName().getString(); cooldownUntil = tick + 2; webbedUntil.put(en.getId(), tick + 10); }
                }
            }
        }
        lastWater = water; lastScanned = scanned;

        // nearest new source first
        fresh.sort(Comparator.comparingDouble(a -> Vec3.atCenterOf(BlockPos.of(a[0])).distanceToSqr(p.getEyePosition())));
        for (long[] fr : fresh) {
            BlockPos b = BlockPos.of(fr[0]);
            if (!liquidSource(mc.level.getBlockState(b))) continue;
            if (placeWeb(mc, p, b)) {
                drained++;
                Entity en = mc.level.getEntity((int) fr[1]);
                lastResult = "drained " + (en != null ? en.getName().getString() + "'s " : "") + "water";
                JsonObject e = BridgeMod.eventObjPublic("web_drain", lastResult);
                e.addProperty("name", en != null ? en.getName().getString() : "");
                BridgeMod.sendPublic(e);
                lastWater.remove(fr[0]);
            }
        }
    }

    /** Switch to a cobweb, place it into `spot`, switch back – all in this tick. */
    static boolean placeWeb(Minecraft mc, LocalPlayer p, BlockPos spot) {
        int web = Web.hotbar(p, "cobweb");
        if (web < 0) { lastResult = "no cobweb in your hotbar"; return false; }
        if (p.getBoundingBox().intersects(new AABB(spot))) return false;
        BlockHitResult h = clickFor(mc, p, spot);
        if (h == null) return false;
        int prev = BridgeMod.selectedSlotPublic(p);
        float yaw = p.getYRot(), pitch = p.getXRot();
        if (look) Web.face(p, h.getLocation());
        p.getInventory().setSelectedSlot(web);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h);
        p.swing(InteractionHand.MAIN_HAND);
        p.getInventory().setSelectedSlot(prev);            // the game sends the switch back on its next tick
        if (look) {                                         // and turn back where you were looking, quickly
            JsonObject q = new JsonObject();
            q.addProperty("target", "dir"); q.addProperty("yaw", yaw); q.addProperty("pitch", pitch); q.addProperty("ms", 80);
            Aim.command(q, mc, 0);
        }
        return true;
    }

    /** Click on any solid neighbour face (their cobweb counts) within reach. Water itself can't be clicked. */
    static BlockHitResult clickFor(Minecraft mc, LocalPlayer p, BlockPos spot) {
        Vec3 eye = p.getEyePosition();
        double reach = 4.5;
        try { reach = p.blockInteractionRange(); } catch (Throwable ignored) {}
        BlockHitResult best = null; double bestD = Double.MAX_VALUE;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = spot.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) continue;
            net.minecraft.core.Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceTo(hit);
            if (dist > reach || dist >= bestD) continue;
            bestD = dist; best = new BlockHitResult(hit, face, n, false);
        }
        return best;
    }
}
