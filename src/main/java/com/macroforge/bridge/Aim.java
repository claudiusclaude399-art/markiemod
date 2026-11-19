package com.macroforge.bridge;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Aiming engine: picks a target (entity / player / block / position / direction / whatever is under the crosshair)
 * every tick and turns the camera towards it every FRAME (smooth at any fps). Driven by the "aim" and "look" commands.
 */
public final class Aim {
    private Aim() {}

    enum Kind { ENTITY, PLAYER, BLOCK, POS, DIR, CROSSHAIR }

    static final class Req {
        Kind kind = Kind.ENTITY;
        final List<String> exact = new ArrayList<>(), words = new ArrayList<>();
        final Set<String> blockIds = new HashSet<>();
        final List<String> blockWords = new ArrayList<>();
        String playerName = "";
        double range = 6, fov = 360, predictTicks = 0, x, y, z;
        float yaw, pitch;
        String pick = "nearest", part = "chest", face = "visible";
        double speed = 360;            // degrees per second, <= 0 = instant
        boolean smooth = true, track = false, los = true, skipPlayers, playersOnly, skipInvisible = true, sticky = true,
                needAttackHeld, needUseHeld, humanize, livingOnly = true;
        long untilNanos = Long.MAX_VALUE;
        final Set<String> friends = new HashSet<>();
        boolean skipTeam = true, skipPets = true, skipBabies, skipNamed, ballistic = true, autoShoot;
        String style = "smooth";        // smooth | linear | human
        double reactMs = 0;
        float vy, vp; long reactUntil; String lastKey = "";
        // timed turn (old "look" / "look at position" with a duration)
        long startNanos, durNanos; float fromYaw, fromPitch, toYaw, toPitch; boolean timed;
        // runtime
        Entity target; BlockPos block; int lastScan = -1000;
        boolean found, locked; String targetId = "", targetName = ""; double dist;
        Vec3 jitter = Vec3.ZERO; long nextJitter;
    }

    static volatile Req cur;
    static Req last;               // for state after a one-shot finishes
    public static boolean frameHook = false;
    static long lastFrame;
    static final Random rnd = new Random();

    // ---------------------------------------------------------------- commands

    static JsonObject command(JsonObject m, Minecraft mc, int tick) {
        JsonObject r = new JsonObject();
        String t = BridgeMod.str(m, "target", "entity").toLowerCase(Locale.ROOT);
        if (t.equals("stop") || t.equals("off")) { cur = null; r.addProperty("found", false); return r; }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { r.addProperty("found", false); r.addProperty("error", "not in a world"); return r; }
        Req q = new Req();
        switch (t) {
            case "player": q.kind = Kind.PLAYER; break;
            case "block": q.kind = Kind.BLOCK; break;
            case "pos": case "position": q.kind = Kind.POS; break;
            case "dir": case "direction": q.kind = Kind.DIR; break;
            case "crosshair": q.kind = Kind.CROSSHAIR; break;
            default: q.kind = Kind.ENTITY;
        }
        for (String e : BridgeMod.str(m, "entities", "").split(",")) {
            String s = e.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.contains(":")) q.exact.add(s); else q.words.add(s);
        }
        for (String e : BridgeMod.str(m, "blocks", "").split(",")) {
            String s = e.trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            if (s.contains(":")) q.blockIds.add(s); else q.blockWords.add(s);
        }
        q.playerName = BridgeMod.str(m, "player", "").trim().toLowerCase(Locale.ROOT);
        q.range = clamp(BridgeMod.num(m, "range", q.kind == Kind.BLOCK ? 8 : 6), 0.5, q.kind == Kind.BLOCK ? 24 : 256);
        q.fov = clamp(BridgeMod.num(m, "fov", 360), 1, 360);
        q.pick = BridgeMod.str(m, "pick", "nearest");
        q.part = BridgeMod.str(m, "part", "chest");
        q.face = BridgeMod.str(m, "face", "visible");
        q.speed = BridgeMod.num(m, "speed", 360);
        q.smooth = !m.has("smooth") || BridgeMod.bool(m, "smooth");
        q.track = BridgeMod.bool(m, "track");
        q.los = !m.has("los") || BridgeMod.bool(m, "los");
        q.skipPlayers = BridgeMod.bool(m, "skip_players");
        q.playersOnly = BridgeMod.bool(m, "players_only");
        q.skipInvisible = !m.has("skip_invisible") || BridgeMod.bool(m, "skip_invisible");
        q.sticky = !m.has("sticky") || BridgeMod.bool(m, "sticky");
        q.needAttackHeld = BridgeMod.bool(m, "need_attack_held");
        q.needUseHeld = BridgeMod.bool(m, "need_use_held");
        q.humanize = BridgeMod.bool(m, "humanize");
        q.predictTicks = clamp(BridgeMod.num(m, "predict", 0), 0, 20);
        for (String f : BridgeMod.str(m, "friends", "").split(",")) { String n = f.trim().toLowerCase(Locale.ROOT); if (!n.isEmpty()) q.friends.add(n); }
        q.skipTeam = !m.has("skip_team") || BridgeMod.bool(m, "skip_team");
        q.skipPets = !m.has("skip_pets") || BridgeMod.bool(m, "skip_pets");
        q.skipBabies = BridgeMod.bool(m, "skip_babies");
        q.skipNamed = BridgeMod.bool(m, "skip_named");
        q.ballistic = !m.has("ballistic") || BridgeMod.bool(m, "ballistic");
        q.autoShoot = BridgeMod.bool(m, "auto_shoot");
        q.style = BridgeMod.str(m, "style", "smooth");
        q.reactMs = clamp(BridgeMod.num(m, "react_ms", q.style.equals("human") ? 140 : 0), 0, 1000);
        q.x = BridgeMod.num(m, "x", 0); q.y = BridgeMod.num(m, "y", 0); q.z = BridgeMod.num(m, "z", 0);
        q.yaw = (float) BridgeMod.num(m, "yaw", 0); q.pitch = (float) BridgeMod.num(m, "pitch", 0);
        if (BridgeMod.bool(m, "relative")) { q.yaw += p.getYRot(); q.pitch += p.getXRot(); }
        double ms = BridgeMod.num(m, "ms", 0);
        if (q.track) {
            double tms = BridgeMod.num(m, "track_ms", 0);
            q.untilNanos = System.nanoTime() + (long) ((tms > 0 ? tms : 2000) * 1_000_000L);   // 0 = app keeps refreshing
        }
        Req old = cur;
        if (old != null && q.sticky && old.kind == q.kind) { q.target = old.target; q.block = old.block; q.vy = old.vy; q.vp = old.vp; q.lastKey = old.lastKey; q.reactUntil = old.reactUntil; }   // keep the same target on refresh
        q.lastScan = -1000;
        select(q, mc, p, tick);
        if (q.kind == Kind.CROSSHAIR && !q.found) { r.addProperty("found", false); return r; }
        if (!q.track && ms > 0 && (q.kind == Kind.POS || q.kind == Kind.DIR)) {
            float[] yp = q.kind == Kind.DIR ? new float[]{q.yaw, q.pitch} : angles(p.getEyePosition(), new Vec3(q.x, q.y, q.z));
            q.timed = true; q.startNanos = System.nanoTime(); q.durNanos = (long) (ms * 1_000_000L);
            q.fromYaw = p.getYRot(); q.fromPitch = p.getXRot();
            q.toYaw = q.fromYaw + BridgeMod.wrapDeg(yp[0] - q.fromYaw); q.toPitch = clampF(yp[1], -90, 90);
        }
        if (q.kind == Kind.POS || q.kind == Kind.DIR) q.found = true;
        if (q.found || q.track) cur = q; else if (old != null && !old.track) cur = null;
        last = q;
        r.addProperty("found", q.found);
        r.addProperty("target_id", q.targetId);
        r.addProperty("target_name", q.targetName);
        r.addProperty("distance", Math.round(q.dist * 100) / 100.0);
        if (q.block != null) { r.addProperty("x", q.block.getX()); r.addProperty("y", q.block.getY()); r.addProperty("z", q.block.getZ()); }
        else if (q.target != null) { r.addProperty("x", Math.round(q.target.getX() * 100) / 100.0); r.addProperty("y", Math.round(q.target.getY() * 100) / 100.0); r.addProperty("z", Math.round(q.target.getZ() * 100) / 100.0); }
        return r;
    }

    static void addState(JsonObject s) {
        Req q = cur != null ? cur : last;
        s.addProperty("aim_active", cur != null);
        s.addProperty("aim_found", q != null && q.found);
        s.addProperty("aim_locked", q != null && q.locked);
        s.addProperty("aim_target", q == null ? "" : q.targetId);
        s.addProperty("aim_target_name", q == null ? "" : q.targetName);
        s.addProperty("aim_distance", q == null ? 0 : Math.round(q.dist * 100) / 100.0);
    }

    // ---------------------------------------------------------------- per tick: (re)select the target

    static void tick(Minecraft mc, int tick) {
        Req q = cur;
        LocalPlayer p = mc.player;
        if (q == null) return;
        if (p == null || mc.level == null) { cur = null; return; }
        if (q.track && System.nanoTime() > q.untilNanos) { cur = null; return; }
        select(q, mc, p, tick);
        if (!frameHook) frame();   // fallback: turn once per tick
        if (q.autoShoot && q.found && q.locked && mc.screen == null) autoShoot(mc, p);
    }

    // ---------------------------------------------------------------- projectiles

    /** {speed, gravity, drag, ready} for what you're holding / drawing, or null if it isn't a projectile weapon. */
    static double[] projectile(LocalPlayer p) {
        ItemStack st = p.isUsingItem() ? p.getUseItem() : p.getMainHandItem();
        if (st == null || st.isEmpty()) st = p.getOffhandItem();
        if (st == null || st.isEmpty()) return null;
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        boolean using = p.isUsingItem() && p.getUseItem() == st;
        switch (id) {
            case "bow": {
                if (!using) return new double[]{3.0, 0.05, 0.99, 0};
                float f = p.getTicksUsingItem() / 20f; f = (f * f + f * 2f) / 3f; if (f > 1f) f = 1f;
                return new double[]{Math.max(0.3, f * 3.0), 0.05, 0.99, f >= 1f ? 1 : 0};
            }
            case "crossbow": return new double[]{3.15, 0.05, 0.99, CrossbowItem.isCharged(st) ? 1 : 0};
            case "trident": return new double[]{2.5, 0.05, 0.99, using && p.getTicksUsingItem() >= 10 ? 1 : 0};
            case "snowball": case "egg": case "ender_pearl": case "blue_egg": case "brown_egg": return new double[]{1.5, 0.03, 0.99, 0};
            case "splash_potion": case "lingering_potion": return new double[]{0.5, 0.05, 0.99, 0};
            case "experience_bottle": return new double[]{0.7, 0.07, 0.99, 0};
            case "wind_charge": return new double[]{1.5, 0.0, 1.0, 0};
            default: return null;
        }
    }

    /** Height reached (relative to launch) when the projectile has travelled `dist` horizontally, and the ticks it took. */
    static double[] fly(double speed, double g, double drag, double pitchRad, double dist) {
        double vx = speed * Math.cos(pitchRad), vy = speed * Math.sin(pitchRad), x = 0, y = 0;
        for (int t = 1; t <= 300; t++) {
            double nx = x + vx, ny = y + vy;
            if (nx >= dist) { double f = vx == 0 ? 1 : (dist - x) / vx; return new double[]{y + vy * f, t - 1 + f}; }
            x = nx; y = ny; vx *= drag; vy = vy * drag - g;
            if (vx < 1e-4) break;
        }
        return new double[]{-1e9, 300};
    }

    /** Yaw/pitch that land a projectile on the moving target (null if you're not holding one). */
    static float[] ballistic(LocalPlayer p, Entity en, Vec3 aimPoint, Vec3 eye, float pt) {
        double[] pr = projectile(p);
        if (pr == null) return null;
        Vec3 start = eye.add(0, -0.1, 0);
        Vec3 mv = en.getDeltaMovement();
        double myx = p.getDeltaMovement().x, myz = p.getDeltaMovement().z;
        Vec3 tgt = aimPoint; double ticks = 0;
        double pitch = 0;
        for (int it = 0; it < 3; it++) {                         // lead: aim where they'll be when it arrives
            tgt = aimPoint.add((mv.x - myx) * ticks, en.onGround() ? 0 : mv.y * ticks, (mv.z - myz) * ticks);
            double dx = tgt.x - start.x, dz = tgt.z - start.z, d = Math.sqrt(dx * dx + dz * dz), h = tgt.y - start.y;
            if (pr[1] <= 0) { pitch = Math.atan2(h, d); ticks = d / pr[0]; continue; }
            double lo = Math.toRadians(-85), hi = Math.toRadians(45);   // low (direct) arc
            if (fly(pr[0], pr[1], pr[2], hi, d)[0] < h) { pitch = hi; ticks = fly(pr[0], pr[1], pr[2], hi, d)[1]; continue; }   // out of range: max distance angle
            for (int i = 0; i < 22; i++) { double mid = (lo + hi) / 2; if (fly(pr[0], pr[1], pr[2], mid, d)[0] < h) lo = mid; else hi = mid; }
            pitch = (lo + hi) / 2; ticks = fly(pr[0], pr[1], pr[2], pitch, d)[1];
        }
        float yaw = angles(start, tgt)[0];
        return new float[]{yaw, clampF((float) -Math.toDegrees(pitch), -90, 90)};
    }

    static void autoShoot(Minecraft mc, LocalPlayer p) {
        double[] pr = projectile(p);
        if (pr == null || pr[3] < 1 || mc.gameMode == null) return;
        ItemStack st = p.isUsingItem() ? p.getUseItem() : p.getMainHandItem();
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        if (id.equals("crossbow")) BridgeMod.pressUse();       // fire the loaded crossbow
        else if (p.isUsingItem()) mc.gameMode.releaseUsingItem(p);   // let go of the fully drawn bow / trident
    }

    static void select(Req q, Minecraft mc, LocalPlayer p, int tick) {
        switch (q.kind) {
            case POS: q.found = true; q.dist = p.getEyePosition().distanceTo(new Vec3(q.x, q.y, q.z)); q.targetId = "position"; q.targetName = fmt(q.x) + " " + fmt(q.y) + " " + fmt(q.z); return;
            case DIR: q.found = true; q.targetId = "direction"; q.targetName = "yaw " + fmt(q.yaw) + ", pitch " + fmt(q.pitch); return;
            case BLOCK: selectBlock(q, mc, p, tick); return;
            case CROSSHAIR:
                if (q.target == null && q.block == null) {
                    HitResult h = mc.hitResult;
                    if (h instanceof EntityHitResult eh) q.target = eh.getEntity();
                    else if (h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK) q.block = bh.getBlockPos();
                }
                if (q.target != null) { if (!q.target.isAlive() || q.target.isRemoved()) { q.found = false; q.target = null; return; } describe(q, q.target, p); }
                else if (q.block != null) { q.found = true; q.dist = Math.sqrt(q.block.getCenter().distanceToSqr(p.getEyePosition())); q.targetId = blockId(mc, q.block); q.targetName = q.targetId; }
                else q.found = false;
                return;
            default: selectEntity(q, mc, p);
        }
    }

    static boolean entityOk(Req q, Entity en, LocalPlayer p) {
        if (en == p || !en.isAlive() || en.isRemoved() || en.isSpectator()) return false;
        boolean isPlayer = en instanceof Player;
        if (q.kind == Kind.PLAYER) {
            if (!isPlayer) return false;
            if (!q.playerName.isEmpty()) {
                String n = en.getName().getString().toLowerCase(Locale.ROOT);
                if (!(n.equals(q.playerName) || n.contains(q.playerName))) return false;
            }
        } else {
            if ((q.skipPlayers && isPlayer) || (q.playersOnly && !isPlayer)) return false;
            if (!matches(q, en)) return false;
            if (q.exact.isEmpty() && q.words.isEmpty() && !(en instanceof LivingEntity)) return false;   // "any" = living things only
        }
        if (q.skipInvisible && en.isInvisible()) return false;
        if (!friendlyOk(p, en, q.friends, q.skipTeam, q.skipPets)) return false;
        if (q.skipBabies && en instanceof LivingEntity lb && lb.isBaby()) return false;
        if (q.skipNamed && !isPlayer && en.hasCustomName()) return false;
        double d = Math.sqrt(en.getBoundingBox().distanceToSqr(p.getEyePosition()));
        if (d > q.range) return false;
        if (q.fov < 360 && angleTo(p, en.getBoundingBox().getCenter()) > q.fov / 2) return false;
        if (q.los && !p.hasLineOfSight(en)) return false;
        return true;
    }

    /** false for friends (by name), teammates and your own tamed pets. Shared with auto-hit. */
    public static boolean friendlyOk(LocalPlayer p, Entity en, Set<String> friends, boolean skipTeam, boolean skipPets) {
        if (en instanceof Player && !friends.isEmpty() && friends.contains(en.getName().getString().toLowerCase(Locale.ROOT))) return false;
        if (skipTeam && en.getTeam() != null && p.isAlliedTo(en)) return false;
        if (skipPets && en instanceof TamableAnimal ta && ta.isOwnedBy(p)) return false;
        return true;
    }

    static boolean matches(Req q, Entity en) {
        if (q.exact.isEmpty() && q.words.isEmpty()) return true;
        String id = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(en.getType())).toLowerCase(Locale.ROOT);
        if (q.exact.contains(id)) return true;
        String name = en.getName().getString().toLowerCase(Locale.ROOT);
        for (String w : q.words) if (id.contains(w) || name.contains(w)) return true;
        return false;
    }

    static void selectEntity(Req q, Minecraft mc, LocalPlayer p) {
        if (q.sticky && q.target != null && entityOk(q, q.target, p)) { describe(q, q.target, p); return; }
        Entity best = null; double bestScore = Double.MAX_VALUE;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!entityOk(q, en, p)) continue;
            double score;
            switch (q.pick) {
                case "crosshair": score = angleTo(p, en.getBoundingBox().getCenter()); break;
                case "lowest_health": score = en instanceof LivingEntity le ? le.getHealth() * 1000 + en.distanceTo(p) : 1e9; break;
                case "farthest": score = -en.distanceTo(p); break;
                default: score = en.getBoundingBox().distanceToSqr(p.getEyePosition());
            }
            if (score < bestScore) { bestScore = score; best = en; }
        }
        q.target = best;
        if (best == null) { q.found = false; q.targetId = ""; q.targetName = ""; q.dist = 0; return; }
        describe(q, best, p);
    }

    static void describe(Req q, Entity en, LocalPlayer p) {
        q.found = true;
        q.targetId = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(en.getType()));
        q.targetName = en.getName().getString();
        q.dist = Math.sqrt(en.getBoundingBox().distanceToSqr(p.getEyePosition()));
    }

    static boolean blockMatches(Req q, String id) {
        if (q.blockIds.isEmpty() && q.blockWords.isEmpty()) return false;
        if (q.blockIds.contains(id)) return true;
        for (String w : q.blockWords) if (id.contains(w)) return true;
        return false;
    }

    static String blockId(Minecraft mc, BlockPos b) { return String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(b).getBlock())); }

    static void selectBlock(Req q, Minecraft mc, LocalPlayer p, int tick) {
        Vec3 eye = p.getEyePosition();
        if (q.block != null && blockMatches(q, blockId(mc, q.block)) && Math.sqrt(q.block.getCenter().distanceToSqr(eye)) <= q.range + 0.9
                && (q.sticky || tick - q.lastScan < 10) && (!q.los || visible(mc, p, q.block))) {
            q.found = true; q.dist = Math.sqrt(q.block.getCenter().distanceToSqr(eye)); return;
        }
        if (tick - q.lastScan < 5 && q.block == null) return;   // nothing found recently: rescan 4×/s
        q.lastScan = tick;
        BlockPos c = p.blockPosition().above();
        int r = (int) Math.ceil(q.range);
        BlockPos best = null; double bestD = Double.MAX_VALUE; int foundShell = -1;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int d = 0; d <= r; d++) {
            if (foundShell >= 0 && d > foundShell + 1) break;   // nearest shell (+1 for diagonal) is enough
            for (int dx = -d; dx <= d; dx++) for (int dy = -d; dy <= d; dy++) for (int dz = -d; dz <= d; dz++) {
                if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != d) continue;   // shell surface only
                mp.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                if (mc.level.getBlockState(mp).isAir()) continue;
                if (!blockMatches(q, String.valueOf(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(mp).getBlock())))) continue;
                double dd = Math.sqrt(mp.getCenter().distanceToSqr(eye));
                if (dd > q.range + 0.9) continue;
                double score = q.pick.equals("crosshair") ? angleTo(p, mp.getCenter()) : dd;
                if (score >= bestD) continue;
                BlockPos im = mp.immutable();
                if (q.los && !visible(mc, p, im)) continue;
                if (q.fov < 360 && angleTo(p, im.getCenter()) > q.fov / 2) continue;
                bestD = score; best = im; if (foundShell < 0) foundShell = d;
            }
        }
        q.block = best;
        if (best == null) { q.found = false; q.targetId = ""; q.targetName = ""; return; }
        q.found = true; q.targetId = blockId(mc, best); q.targetName = q.targetId + " @ " + best.getX() + " " + best.getY() + " " + best.getZ();
        q.dist = Math.sqrt(best.getCenter().distanceToSqr(eye));
    }

    static boolean visible(Minecraft mc, LocalPlayer p, BlockPos b) {
        Vec3 eye = p.getEyePosition();
        BlockHitResult h = mc.level.clip(new ClipContext(eye, b.getCenter(), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return h.getType() == HitResult.Type.MISS || h.getBlockPos().equals(b);
    }

    // ---------------------------------------------------------------- per frame: turn the camera

    public static void frame() {
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0.016f : (float) Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        Req q = cur;
        if (q == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.screen != null || !p.isAlive()) return;
        if (q.needAttackHeld && !mc.options.keyAttack.isDown()) { q.locked = false; return; }
        if (q.needUseHeld && !mc.options.keyUse.isDown()) { q.locked = false; return; }

        if (q.timed) {
            float t = q.durNanos <= 0 ? 1f : Math.min(1f, (now - q.startNanos) / (float) q.durNanos);
            float s = t * t * (3 - 2 * t);
            setRot(p, q.fromYaw + (q.toYaw - q.fromYaw) * s, q.fromPitch + (q.toPitch - q.fromPitch) * s);
            if (t >= 1f) { q.locked = true; cur = null; }
            return;
        }
        if (!q.found) { q.locked = false; return; }

        float pt = partial(mc);
        Vec3 eye = p.getEyePosition(pt);
        float[] want;
        if (q.kind == Kind.DIR) want = new float[]{q.yaw, clampF(q.pitch, -90, 90)};
        else {
            Vec3 pt3 = point(q, mc, p, pt, eye);
            if (pt3 == null) { q.locked = false; return; }
            float[] ball = q.ballistic && q.target != null ? ballistic(p, q.target, pt3, eye, pt) : null;   // bow / trident / throwables: aim above for the drop, ahead for movement
            want = ball != null ? ball : angles(eye, pt3);
        }
        String key = q.target != null ? "e" + q.target.getId() : q.block != null ? "b" + q.block.asLong() : q.kind.name();
        if (!key.equals(q.lastKey)) { q.lastKey = key; if (q.reactMs > 0) q.reactUntil = now + (long) ((q.reactMs * (0.8 + rnd.nextDouble() * 0.5)) * 1_000_000L); }
        float dy = BridgeMod.wrapDeg(want[0] - p.getYRot()), dp = want[1] - p.getXRot();
        float diff = (float) Math.sqrt(dy * dy + dp * dp);
        if (now < q.reactUntil) { /* reaction time: haven't "noticed" the new target yet */ }
        else if (q.speed <= 0 || diff < 0.05f) { setRot(p, p.getYRot() + dy, want[1]); q.vy = q.vp = 0; }
        else if (q.style.equals("human")) {
            // critically-damped spring: accelerates, then settles without overshoot – like a hand on a mouse
            float w = (float) clamp(q.speed / 45.0, 3, 40);
            q.vy += (w * w * dy - 2 * w * q.vy) * dt;
            q.vp += (w * w * dp - 2 * w * q.vp) * dt;
            float sp = (float) Math.sqrt(q.vy * q.vy + q.vp * q.vp), cap = (float) (q.speed * 1.3);
            if (sp > cap) { q.vy *= cap / sp; q.vp *= cap / sp; }
            setRot(p, p.getYRot() + q.vy * dt, p.getXRot() + q.vp * dt);
        } else {
            float step = (float) (q.speed * dt);
            if (q.smooth && !q.style.equals("linear")) step *= (float) clamp(diff / 25.0, 0.18, 1.0);   // ease out near the target
            if (step >= diff) setRot(p, p.getYRot() + dy, want[1]);
            else setRot(p, p.getYRot() + dy * step / diff, p.getXRot() + dp * step / diff);
        }
        float ny = BridgeMod.wrapDeg(want[0] - p.getYRot()), np = want[1] - p.getXRot();
        q.locked = Math.sqrt(ny * ny + np * np) < 1.5 || onTarget(q, mc);
        if (!q.track && q.locked) cur = null;   // one-shot: done once we're on it
    }

    static boolean onTarget(Req q, Minecraft mc) {
        HitResult h = mc.hitResult;
        if (q.target != null && h instanceof EntityHitResult eh) return eh.getEntity() == q.target;
        if (q.block != null && h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK) return bh.getBlockPos().equals(q.block);
        return false;
    }

    static Vec3 point(Req q, Minecraft mc, LocalPlayer p, float pt, Vec3 eye) {
        Vec3 v;
        if (q.kind == Kind.POS) return new Vec3(q.x, q.y, q.z);
        if (q.block != null && (q.kind == Kind.BLOCK || q.kind == Kind.CROSSHAIR)) {
            Vec3 c = q.block.getCenter();
            switch (q.face) {
                case "top": v = c.add(0, 0.5, 0); break;
                case "bottom": v = c.add(0, -0.5, 0); break;
                case "center": v = c; break;
                default: { // the face pointing at you
                    Vec3 d = eye.subtract(c);
                    double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
                    if (ax >= ay && ax >= az) v = c.add(Math.signum(d.x) * 0.5, 0, 0);
                    else if (ay >= az) v = c.add(0, Math.signum(d.y) * 0.5, 0);
                    else v = c.add(0, 0, Math.signum(d.z) * 0.5);
                }
            }
            return v;
        }
        Entity en = q.target;
        if (en == null) return null;
        Vec3 base = en.getPosition(pt);
        double h = en.getBbHeight();
        switch (q.part) {
            case "head": v = en.getEyePosition(pt); break;
            case "feet": v = base.add(0, Math.min(0.2, h * 0.2), 0); break;
            case "closest": {   // the point on the hitbox nearest to where you're already aiming = least turning
                AABB bb = en.getBoundingBox().move(base.subtract(en.position())).deflate(0.05);
                double dist = Math.sqrt(bb.distanceToSqr(eye)) + 0.3;
                Vec3 ray = eye.add(p.getViewVector(pt).scale(dist));
                v = new Vec3(clamp(ray.x, bb.minX, bb.maxX), clamp(ray.y, bb.minY, bb.maxY), clamp(ray.z, bb.minZ, bb.maxZ));
                break;
            }
            default: v = base.add(0, h * 0.5, 0);
        }
        if (q.predictTicks > 0) { Vec3 mv = en.getDeltaMovement(); v = v.add(mv.x * q.predictTicks, 0, mv.z * q.predictTicks); }
        if (q.humanize) {
            long now = System.nanoTime();
            if (now > q.nextJitter) {
                double w = en.getBbWidth() * 0.18, hh = h * 0.12;
                q.jitter = new Vec3(rnd.nextGaussian() * w, rnd.nextGaussian() * hh, rnd.nextGaussian() * w);
                q.nextJitter = now + 180_000_000L + (long) (rnd.nextDouble() * 220_000_000L);
            }
            v = v.add(q.jitter);
        }
        return v;
    }

    static void setRot(LocalPlayer p, float yaw, float pitch) {
        pitch = clampF(pitch, -90, 90);
        float dy = yaw - p.getYRot(), dp = pitch - p.getXRot();
        p.setYRot(yaw); p.setXRot(pitch);
        p.yRotO += dy; p.xRotO += dp;   // like mouse turning: no interpolation lag on screen
    }

    // ---------------------------------------------------------------- helpers

    static float partial(Minecraft mc) {
        try { return mc.getDeltaTracker().getGameTimeDeltaPartialTick(true); } catch (Throwable t) { return 1f; }
    }

    static float[] angles(Vec3 eye, Vec3 to) {
        double dx = to.x - eye.x, dy = to.y - eye.y, dz = to.z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    static double angleTo(LocalPlayer p, Vec3 point) {
        Vec3 dir = point.subtract(p.getEyePosition()).normalize();
        double dot = clamp(dir.dot(p.getViewVector(1f)), -1, 1);
        return Math.toDegrees(Math.acos(dot));
    }

    static double clamp(double v, double lo, double hi) { return v < lo ? lo : v > hi ? hi : v; }
    static float clampF(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
    static String fmt(double d) { return String.valueOf(Math.round(d * 10) / 10.0); }
}
