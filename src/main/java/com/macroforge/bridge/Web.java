package com.macroforge.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * "Stun web": (1) axe hit on their raised shield (disables it), (2) after a delay a quick knock-up hit,
 * (3) a cobweb where the knock-up PHYSICS says they'll be in the air (predicted from Minecraft's own knockback,
 * gravity and drag – no waiting to see them leave the ground), (4) switch back + turn back onto them.
 *
 * Steps run right AFTER Minecraft handles the keys (MinecraftMixin, handleKeybinds TAIL), so a hotbar switch and the
 * hit/placement that needs it happen in the same tick. Every step has its own delay (ms, rounded to game ticks).
 */
public final class Web {
    private Web() {}

    enum Stage { IDLE, START, STUN, KNOCK, HEAD, BACK }

    static Stage stage = Stage.IDLE;
    static Entity target;
    static int prevSlot = -1, webSlot = -1, axeSlot = -1, startTick, placed;
    static boolean breakShield = true, head = false, look = true, switchBack = true, knock = true, needShield = false;
    static double startDelayMs = 0, stunDelayMs = 550, webDelayMs = 50, headDelayMs = 50, backDelayMs = 50, turnBackMs = 120;
    static long stageAt;                    // when the current stage began (ms)
    static float prevYaw, prevPitch;
    static String result = "", lastTarget = "";
    public static boolean tailHook = false;
    // prediction made at the knock-up hit
    static Vec3[] path;                     // predicted feet position for ticks 0..N after the hit
    static int hitTick = -1;
    static BlockPos predicted;

    public static boolean busy() { return stage != Stage.IDLE; }

    static void addState(JsonObject s) {
        s.addProperty("web_busy", busy());
        s.addProperty("web_result", result);
        s.addProperty("web_target", lastTarget);
        LocalPlayer p = Minecraft.getInstance().player;
        s.addProperty("web_count", p == null ? 0 : Pvp.count(p, "cobweb"));
    }

    // ---------------------------------------------------------------- start

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
        startDelayMs = ms(m, "start_delay", 0);
        stunDelayMs = ms(m, "stun_delay", 550);
        webDelayMs = ms(m, "web_delay", 50);
        headDelayMs = ms(m, "head_delay", 50);
        backDelayMs = ms(m, "back_delay", 50);
        turnBackMs = ms(m, "turn_back_ms", 120);
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
        boolean shieldUp = shielding(t);
        if (needShield && !shieldUp) return fail(r, "their shield isn't up");

        target = t; lastTarget = t.getName().getString(); placed = 0; path = null; predicted = null; hitTick = -1;
        prevSlot = BridgeMod.selectedSlotPublic(p); prevYaw = p.getYRot(); prevPitch = p.getXRot();
        startTick = tick; result = "";
        axeSlot = -1;
        if (breakShield && shieldUp) axeSlot = BridgeMod.itemId(p.getMainHandItem()).endsWith("_axe") ? prevSlot : hotbar(p, "_axe");
        enter(Stage.START);
        if (startDelayMs <= 0) preselect(mc);   // so the switch is applied in this very tick's key handling
        r.addProperty("ok", true);
        r.addProperty("target", lastTarget);
        r.addProperty("stun", axeSlot >= 0);
        return r;
    }

    static double ms(JsonObject m, String k, double def) { return Math.max(0, Math.min(3000, BridgeMod.num(m, k, def))); }

    static boolean shielding(Entity t) { return t instanceof LivingEntity sl && sl.isUsingItem() && BridgeMod.itemId(sl.getUseItem()).endsWith("shield"); }

    static boolean ok(LocalPlayer p, Entity en, Set<String> friends, boolean playersOnly, double range) {
        if (en == p || !en.isAlive() || !(en instanceof LivingEntity) || en.isSpectator()) return false;
        if (playersOnly && !(en instanceof Player)) return false;
        if (!Aim.friendlyOk(p, en, friends, true, true)) return false;
        return reach(p, en) <= range + 1.0;
    }

    static JsonObject fail(JsonObject r, String why) { result = why; r.addProperty("ok", false); r.addProperty("error_text", why); return r; }
    static double reach(LocalPlayer p, Entity t) { return Math.sqrt(t.getBoundingBox().distanceToSqr(p.getEyePosition())); }
    static int hotbar(LocalPlayer p, String idEnd) { for (int i = 0; i < 9; i++) if (BridgeMod.itemId(p.getInventory().getItem(i)).endsWith(idEnd)) return i; return -1; }

    static void select(Minecraft mc, int slot) {
        if (slot >= 0 && slot != BridgeMod.selectedSlotPublic(mc.player)) BridgeMod.pressKey(mc.options.keyHotbarSlots[slot]);
    }

    static void enter(Stage s) { stage = s; stageAt = System.currentTimeMillis(); }
    static boolean waited(double ms) { return System.currentTimeMillis() - stageAt >= ms - 25; }   // -25: land on the nearest tick, not one late
    static boolean holding(LocalPlayer p, String idEnd) { return BridgeMod.itemId(p.getMainHandItem()).endsWith(idEnd); }

    /** Switch to what the next step needs early, so it's in hand when that step's delay is over. */
    static void preselect(Minecraft mc) {
        switch (stage) {
            case START: select(mc, axeSlot >= 0 ? axeSlot : knock ? prevSlot : webSlot); break;
            case STUN: select(mc, knock ? prevSlot : webSlot); break;
            case KNOCK: select(mc, webSlot); break;
            default: break;
        }
    }

    // ---------------------------------------------------------------- steps (after key handling each tick)

    /** Called right after Minecraft's handleKeybinds (or at the end of the tick if that hook is missing). */
    public static void step(Minecraft mc, int tick) {
        if (stage == Stage.IDLE) return;
        LocalPlayer p = mc.player;
        int limit = 20 + (int) ((startDelayMs + stunDelayMs + webDelayMs + headDelayMs + backDelayMs) / 50);
        if (p == null || mc.level == null || mc.gameMode == null || tick - startTick > limit || target == null) { finish(mc, result.isEmpty() ? "timed out" : result); return; }
        switch (stage) {
            case START: {
                if (!waited(startDelayMs)) return;
                if (axeSlot >= 0) {
                    if (!holding(p, "_axe")) { select(mc, axeSlot); return; }
                    if (reach(p, target) > 3.0) { finish(mc, "too far for the stun hit"); return; }
                    hit(mc, p);                               // 1. stun: their shield is disabled for 5 s
                    enter(Stage.STUN); preselect(mc);         // weapon goes back in hand during the delay
                    if (look) face(p, target.getBoundingBox().getCenter());
                } else if (knock) { enter(Stage.STUN); stageAt -= (long) stunDelayMs; preselect(mc); }   // no shield/axe: no stun, no wait
                else { enter(Stage.KNOCK); hitTick = tick; preselect(mc); }
                return;
            }
            case STUN: {
                if (!knock) { enter(Stage.KNOCK); hitTick = tick; return; }
                if (look) face(p, target.getBoundingBox().getCenter());
                if (!waited(stunDelayMs)) return;
                if (prevSlot != BridgeMod.selectedSlotPublic(p)) { select(mc, prevSlot); return; }
                if (!target.isAlive()) { finish(mc, "target gone"); return; }
                if (reach(p, target) > 3.0) { finish(mc, "too far for the knock-up hit"); return; }
                path = predict(p, target);                    // where the knockback will carry them, tick by tick
                hit(mc, p);                                   // 2. knock up
                hitTick = tick;
                enter(Stage.KNOCK);
                if (webDelayMs <= 0) {                        // 0 ms: switch + web in this same tick
                    p.getInventory().setSelectedSlot(webSlot);
                    BlockHitResult h = webClick(mc, p, 0);
                    if (h != null) {
                        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h);   // sends the slot change first, then the web
                        p.swing(InteractionHand.MAIN_HAND);
                        placed++;
                        if (look) face(p, h.getLocation());
                        enter(head ? Stage.HEAD : Stage.BACK);
                        return;
                    }
                }
                preselect(mc);                                // cobweb goes in hand right away
                aimAtPrediction(mc, p, tick + 1);
                return;
            }
            case KNOCK: {                                     // 3. web where they'll be in the air
                if (!holding(p, "cobweb")) { select(mc, webSlot); aimAtPrediction(mc, p, tick + 1); return; }
                if (!waited(webDelayMs)) { aimAtPrediction(mc, p, tick + 1); return; }
                BlockHitResult h = webClick(mc, p, tick - hitTick);
                if (h == null) {
                    if (tick - hitTick <= 10) { aimAtPrediction(mc, p, tick + 1); return; }   // keep trying while they fly
                    finish(mc, whyNot); return;
                }
                if (look) face(p, h.getLocation());
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h);
                p.swing(InteractionHand.MAIN_HAND);
                placed++;
                enter(head ? Stage.HEAD : Stage.BACK);
                return;
            }
            case HEAD: {
                if (!waited(headDelayMs)) return;
                if (holding(p, "cobweb")) {
                    BlockPos above = predicted != null ? predicted.above() : target.blockPosition().above();
                    BlockHitResult h = placeAt(mc, p, above);
                    if (h != null) { if (look) face(p, h.getLocation()); mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, h); p.swing(InteractionHand.MAIN_HAND); placed++; }
                }
                enter(Stage.BACK);
                return;
            }
            case BACK: {
                if (!waited(backDelayMs)) return;
                finish(mc, placed > 0 ? (axeSlot >= 0 ? "stunned + " : "") + (knock ? "knocked up + " : "") + "webbed " + lastTarget + (placed > 1 ? " (2 webs)" : "") : "not placed");
                return;
            }
            default: finish(mc, "");
        }
    }

    static void hit(Minecraft mc, LocalPlayer p) { mc.gameMode.attack(p, target); p.swing(InteractionHand.MAIN_HAND); }

    static void face(LocalPlayer p, Vec3 at) {
        float[] yp = Aim.angles(p.getEyePosition(), at);
        Aim.setRot(p, p.getYRot() + BridgeMod.wrapDeg(yp[0] - p.getYRot()), yp[1]);
    }

    // ---------------------------------------------------------------- prediction

    /**
     * Knock-up flight from Minecraft's own numbers: knockback 0.4 × (1 − knockback resistance) away from where you
     * face, vertical min(0.4, vy/2 + 0.4) if on the ground; then each tick: move, vy = (vy − 0.08) × 0.98, vx/vz × 0.91.
     */
    static Vec3[] predict(LocalPlayer p, Entity t) {
        double res = 0;
        if (t instanceof LivingEntity le) { try { res = le.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE); } catch (Throwable ignored) {} }
        double s = 0.4 * Math.max(0, 1 - res);
        double yaw = Math.toRadians(p.getYRot());
        double kx = -Math.sin(yaw), kz = Math.cos(yaw);       // the way you're facing = the way they fly
        Vec3 v = t.getDeltaMovement();
        double vx = v.x / 2 + kx * s, vz = v.z / 2 + kz * s;
        double vy = t.onGround() ? Math.min(0.4, v.y / 2 + s) : v.y;
        Vec3 pos = t.position();
        Minecraft mc = Minecraft.getInstance();
        Vec3[] out = new Vec3[16];
        out[0] = pos;
        for (int i = 1; i < out.length; i++) {
            pos = pos.add(vx, vy, vz);
            BlockPos below = BlockPos.containing(pos.x, pos.y - 0.01, pos.z);
            if (vy < 0 && !mc.level.getBlockState(below).getCollisionShape(mc.level, below).isEmpty() && pos.y - below.getY() < 1.0) {
                pos = new Vec3(pos.x, below.getY() + 1.0, pos.z); vy = 0;   // landed
            }
            out[i] = pos;
            vy = (vy - 0.08) * 0.98; vx *= 0.91; vz *= 0.91;
        }
        return out;
    }

    static String whyNot = "";

    /** Where to click to web them `ticksAfterHit` ticks after the knock-up (null + whyNot if nowhere). */
    static BlockHitResult webClick(Minecraft mc, LocalPlayer p, int ticksAfterHit) {
        List<BlockPos> spots = new ArrayList<>();
        if (path != null) {
            // the block their feet will be in on the tick the web lands (next tick), then the ones after it, then the landing spot
            for (int i = Math.max(1, ticksAfterHit + 1); i < path.length; i++) addOnce(spots, BlockPos.containing(path[i]));
            addOnce(spots, BlockPos.containing(path[path.length - 1]));
        }
        BlockPos now = target.blockPosition();
        addOnce(spots, now);
        for (int i = 1; i <= 3; i++) { BlockPos c = now.below(i); if (!mc.level.getBlockState(c).canBeReplaced()) break; addOnce(spots, c); }
        String first = null;
        for (BlockPos c : spots) {
            BlockHitResult h = placeAt(mc, p, c);
            if (h != null) { predicted = c; return h; }
            if (first == null) first = whyNot;
            if ("already webbed".equals(whyNot)) return null;
        }
        whyNot = first == null ? "nowhere to place the web" : first;
        return null;
    }

    static void addOnce(List<BlockPos> l, BlockPos b) { if (!l.contains(b)) l.add(b); }

    static void aimAtPrediction(Minecraft mc, LocalPlayer p, int tick) {
        if (!look || hitTick < 0) return;
        BlockHitResult h = webClick(mc, p, tick - hitTick);
        if (h != null) face(p, h.getLocation());
    }

    /** Click that puts a cobweb into `spot` (needs a solid neighbour within reach), or null. */
    static BlockHitResult placeAt(Minecraft mc, LocalPlayer p, BlockPos spot) {
        BlockState here = mc.level.getBlockState(spot);
        if (BridgeMod.blockId(here).endsWith("cobweb")) { whyNot = "already webbed"; return null; }
        if (!here.canBeReplaced()) { whyNot = "no space for a web there"; return null; }
        if (p.getBoundingBox().intersects(new AABB(spot))) { whyNot = "too close – it would web you"; return null; }
        Vec3 eye = p.getEyePosition();
        double reach = 4.5;
        try { reach = p.blockInteractionRange(); } catch (Throwable ignored) {}
        BlockHitResult best = null; double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = spot.relative(d);
            BlockState ns = mc.level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double dist = eye.distanceTo(hit);
            if (dist > reach) continue;
            double score = dist + (d == Direction.DOWN ? -1.0 : 0);
            if (score < bestD) { bestD = score; best = new BlockHitResult(hit, face, n, false); }
        }
        if (best == null) whyNot = "nothing to place the web against within reach";
        return best;
    }

    // ---------------------------------------------------------------- finish

    static void finish(Minecraft mc, String why) {
        LocalPlayer p = mc.player;
        if (p != null) {
            if (switchBack && prevSlot >= 0) select(mc, prevSlot);
            if (look) {
                JsonObject q = new JsonObject();
                if (target != null && target.isAlive()) { q.addProperty("target", "pos"); Vec3 c = target.getBoundingBox().getCenter(); q.addProperty("x", c.x); q.addProperty("y", c.y + 0.4); q.addProperty("z", c.z); }
                else { q.addProperty("target", "dir"); q.addProperty("yaw", prevYaw); q.addProperty("pitch", prevPitch); }
                q.addProperty("ms", Math.max(1, turnBackMs));
                if (turnBackMs <= 0) q.addProperty("speed", 0);
                Aim.command(q, mc, 0);
            }
        }
        result = why;
        JsonObject e = BridgeMod.eventObjPublic("web", why);
        e.addProperty("ok", placed > 0);
        e.addProperty("target", lastTarget);
        BridgeMod.sendPublic(e);
        stage = Stage.IDLE; target = null; path = null;
    }
}
