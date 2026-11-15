package dev.pvpmod.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every check over a fight, tick by tick. Used live while recording (step() each tick) and again when a saved
 * fight is opened, so results are always reproducible from the recording alone.
 *
 * Everything here works from what a client can see of another player: positions and rotations from the server,
 * arm swings, damage packets (with the attacker), equipment and totem pops. Opponent rotations are only sent with
 * ~1.4 degree precision and players update every 2 ticks, so the checks are deliberately lenient.
 */
public final class Analyzer {
    public static final double REACH_LIMIT = 3.0;

    private final List<Frame> frames;
    private final List<FightEvent> events;
    /** 1 = normal, &lt;1 stricter, &gt;1 more relaxed. */
    private final float sens;

    public final Series reach = new Series(), cps = new Series(), aim = new Series(), speed = new Series(),
        myHp = new Series(), opHp = new Series(), sus = new Series();
    public final List<Flag> flags = new ArrayList<>();
    private final Map<CheckType, Double> keep = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Integer> lastFlag = new EnumMap<>(CheckType.class);

    // stats
    public int hitsGiven, hitsTaken, popsOp, popsMe, crystals;
    public double maxReach, reachSum;
    public int reachCount;
    public float peakCps;

    private int next = 0, eventCursor = 0;
    private int opHurtTick = -1000, opTeleportTick = -1000;

    public Analyzer(List<Frame> frames, List<FightEvent> events, float sens) {
        this.frames = frames;
        this.events = events;
        this.sens = sens <= 0 ? 1f : sens;
    }

    public static Analyzer run(FightRecord r) {
        Analyzer a = new Analyzer(r.frames, r.events, r.sens);
        a.step();
        a.finish();
        return a;
    }

    // ------------------------------------------------------------------ scoring
    private void flag(int t, CheckType c, float sev, double v, String detail, PState where, int cooldown) {
        Integer last = lastFlag.get(c);
        if (cooldown > 0 && last != null && t - last < cooldown) return;
        lastFlag.put(c, t);
        Flag f = new Flag(t, c, sev, v, detail, where);
        flags.add(f);
        keep.put(c, keep.getOrDefault(c, 1.0) * (1.0 - 0.5 * f.sev));
    }

    /** 0..100 score for one check. */
    public float checkScore(CheckType c) {
        return (float) (100.0 * (1.0 - keep.getOrDefault(c, 1.0)));
    }

    /** 0..100 overall suspicion. */
    public float score() {
        double clean = 1.0;
        for (CheckType c : CheckType.values()) clean *= 1.0 - (checkScore(c) / 100.0) * c.weight;
        return (float) (100.0 * (1.0 - clean));
    }

    public int flagCount(CheckType c) {
        int n = 0;
        for (Flag f : flags) if (f.c == c) n++;
        return n;
    }

    public static String verdict(float score) {
        if (score < 20) return "Looks legit";
        if (score < 45) return "Minor anomalies";
        if (score < 75) return "Suspicious";
        return "Likely cheating";
    }

    // ------------------------------------------------------------------ driving
    public void step() {
        while (next < frames.size()) {
            tick(next);
            next++;
        }
    }

    public void finish() {
        resolveVelocity(Integer.MAX_VALUE);
        resolveSnaps(Integer.MAX_VALUE);
    }

    private Frame fr(int t) { return t >= 0 && t < frames.size() ? frames.get(t) : null; }

    private PState op(int t) {
        Frame f = fr(t);
        return f == null || f.op == null || f.op.has(PState.ABSENT) ? null : f.op;
    }

    private PState me(int t) {
        Frame f = fr(t);
        return f == null ? null : f.me;
    }

    // ------------------------------------------------------------------ per tick
    private final ArrayDeque<Integer> swingWindow = new ArrayDeque<>();
    private int swingSum = 0, highCpsRun = 0;
    private final ArrayDeque<Integer> cpsSamples = new ArrayDeque<>();
    private final ArrayDeque<boolean[]> aimWindow = new ArrayDeque<>();
    private int aimHigh = 0, aimLocked = 0;
    private final ArrayDeque<Double> speedWindow = new ArrayDeque<>();
    private int airRun = 0;

    private void tick(int t) {
        Frame f = frames.get(t);
        PState me = f.me, op = op(t);
        myHp.add(me == null ? Float.NaN : me.hp + me.ab);
        opHp.add(op == null ? Float.NaN : op.hp + op.ab);
        reach.add(Float.NaN);

        int lastEvent = eventCursor;
        while (eventCursor < events.size() && events.get(eventCursor).t <= t) eventCursor++;
        for (int i = lastEvent; i < eventCursor; i++) onEvent(t, events.get(i));

        tickClicks(t, op);
        tickAim(t, f, op);
        tickSpeed(t, op);
        tickFlight(t, op);
        resolveVelocity(t);
        resolveSnaps(t);
        sus.add(score());
    }

    // ------------------------------------------------------------------ events
    private final List<int[]> pops = new ArrayList<>();          // {tick, ping}
    private int fastRefills = 0;
    private final ArrayDeque<FightEvent> crystalWindow = new ArrayDeque<>();
    private final List<Object[]> velocityPending = new ArrayList<>();
    private int kbChecked = 0, kbMissing = 0;
    private int lastHitMe = -1000;

    private void onEvent(int t, FightEvent e) {
        switch (e.k) {
            case HIT_ME -> onHitMe(t, e);
            case HIT_OP -> {
                hitsGiven++;
                opHurtTick = t;
                PState op = op(t), me = me(t);
                if (op != null && me != null && !e.b
                    && !op.has(PState.FLUID | PState.VEHICLE | PState.GLIDE | PState.CLIMB)) {
                    double dx = op.x - me.x, dz = op.z - me.z, len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.05) velocityPending.add(new Object[]{t, op.copy(), dx / len, dz / len});
                }
            }
            case BLAST_OP -> opHurtTick = t;
            case POP_OP -> {
                popsOp++;
                opHurtTick = t;
                pops.add(new int[]{t, fr(t) == null ? 0 : fr(t).opg});
            }
            case POP_ME -> popsMe++;
            case REFILL_OP -> onRefill(t, e);
            case CRYSTAL_OP -> onCrystal(t, e);
            default -> {}
        }
    }

    private void onHitMe(int t, FightEvent e) {
        hitsTaken++;
        lastHitMe = t;
        Frame f = fr(t);
        PState op = op(t);
        if (f == null || op == null || op.has(PState.CREATIVE)) return;

        // reach: smallest distance over plausible lag offsets, so lag never counts against them
        int k = clamp(Math.round(f.mp / 50f) + 2, 2, 8);
        double best = Double.MAX_VALUE;
        for (int i = Math.max(0, t - k); i <= t; i++) {
            PState m = me(i);
            if (m == null) continue;
            for (int j = Math.max(0, t - 3); j <= t; j++) {
                PState o = op(j);
                if (o == null) continue;
                best = Math.min(best, boxDistance(o.x, o.eyeY(), o.z, m));
            }
        }
        if (best < 50) {
            reach.set(t, (float) best);
            maxReach = Math.max(maxReach, best);
            reachSum += best;
            reachCount++;
            double limit = REACH_LIMIT + 0.12 * sens;
            if (best > limit) {
                float sev = (float) clamp((best - 3.05) / 0.9, 0.15, 1.0);
                flag(t, CheckType.REACH, sev, best, String.format("Hit you from %.2f blocks (limit %.1f)", best, REACH_LIMIT), op, 0);
            }
        }

        // no-look hit: they were not looking anywhere near you
        double minErr = 180;
        for (int j = Math.max(0, t - 3); j <= t; j++) {
            PState o = op(j);
            for (int i = Math.max(0, j - 2); i <= j; i++) {
                PState m = me(i);
                if (o != null && m != null) minErr = Math.min(minErr, aimError(o, m));
            }
        }
        if (minErr > 60 * sens && minErr < 180) {
            float sev = (float) clamp(0.5 + (minErr - 60) / 120, 0.5, 1.0);
            flag(t, CheckType.KILLAURA, sev, minErr, String.format("Hit you while looking %.0f° away", minErr), op, 0);
        }

        if (e.b) flag(t, CheckType.WALL_HIT, 0.45f, 0, "Hit you with blocks in the way", op, 10);
    }

    private void onRefill(int t, FightEvent e) {
        if (pops.isEmpty()) return;
        int[] pop = pops.remove(pops.size() - 1);
        int ticks = (int) e.v;
        double reaction = Math.max(0, ticks * 50.0 - pop[1] - 50.0);
        PState op = op(t);
        if (reaction < 50 * sens) {
            fastRefills++;
            flag(t, CheckType.AUTO_TOTEM, 0.7f, reaction,
                String.format("New totem %d tick%s after the pop (~%.0f ms reaction after ping)", ticks, ticks == 1 ? "" : "s", reaction), op, 0);
        } else if (reaction < 110 * sens) {
            fastRefills++;
            flag(t, CheckType.AUTO_TOTEM, 0.35f, reaction,
                String.format("Totem refilled in ~%.0f ms (after ping)", reaction), op, 0);
        }
        if (fastRefills >= 3) {
            flag(t, CheckType.AUTO_TOTEM, 0.85f, fastRefills, fastRefills + " inhumanly fast totem refills this fight", op, 400);
        }
    }

    private void onCrystal(int t, FightEvent e) {
        crystals++;
        crystalWindow.addLast(e);
        while (!crystalWindow.isEmpty() && t - crystalWindow.peekFirst().t > 100) crystalWindow.pollFirst();
        PState op = op(t);
        if (e.d > 55 * sens && e.d <= 180) {
            flag(t, CheckType.CRYSTAL_AURA, 0.45f, e.d, String.format("Placed a crystal %.0f° away from where they looked", e.d), op, 20);
        }
        if (crystalWindow.size() >= 12) {
            double life = 0;
            for (FightEvent c : crystalWindow) life += c.v;
            life /= crystalWindow.size();
            if (life <= 1.6 / sens) {
                flag(t, CheckType.CRYSTAL_AURA, 0.7f, life,
                    String.format("%d crystals in 5 s, alive %.1f ticks on average", crystalWindow.size(), life), op, 100);
            }
        }
    }

    // ------------------------------------------------------------------ clicks
    private void tickClicks(int t, PState op) {
        int s = op == null ? 0 : op.s;
        swingWindow.addLast(s);
        swingSum += s;
        if (swingWindow.size() > 20) swingSum -= swingWindow.pollFirst();
        float c = swingSum;
        cps.add(c);
        peakCps = Math.max(peakCps, c);

        if (c >= 20 * sens) highCpsRun++;
        else highCpsRun = 0;
        if (highCpsRun >= 40) {
            float sev = c >= 26 * sens ? 0.9f : 0.55f;
            flag(t, CheckType.AUTOCLICKER, sev, c, String.format("%.0f CPS for over 2 seconds", c), op, 100);
        }

        if (t % 20 == 19) {
            cpsSamples.addLast(swingSum);
            if (cpsSamples.size() > 8) cpsSamples.pollFirst();
            if (cpsSamples.size() == 8) {
                double mean = 0, var = 0, min = 99;
                for (int v : cpsSamples) { mean += v; min = Math.min(min, v); }
                mean /= 8;
                for (int v : cpsSamples) var += (v - mean) * (v - mean);
                double sd = Math.sqrt(var / 8);
                if (min >= 9 && sd < 0.45 / sens) {
                    flag(t, CheckType.AUTOCLICKER, 0.45f, mean,
                        String.format("Clicked %.1f CPS with almost no variation for 8 s (sd %.2f)", mean, sd), op, 160);
                }
            }
        }
    }

    // ------------------------------------------------------------------ aim
    private double prevBearing = Double.NaN;
    private final List<double[]> snapPending = new ArrayList<>();   // {tick, yawDelta, hit(0/1), back(0/1)}

    private void tickAim(int t, Frame f, PState op) {
        PState me = f.me;
        if (op == null || me == null) {
            aim.add(Float.NaN);
            prevBearing = Double.NaN;
            pushAim(false, false);
            return;
        }
        int lag = clamp(Math.round((f.mp + f.opg) / 50f) + 2, 0, 10);
        double bestErr = 180, bearing = Double.NaN, dist = 99;
        for (int d = -1; d <= 1; d++) {
            PState m = me(t - lag + d);
            if (m == null) continue;
            double dx = m.x - op.x, dz = m.z - op.z;
            double b = Math.toDegrees(Math.atan2(-dx, dz));
            double err = Math.abs(PState.wrap((float) (op.yw - b)));
            if (err < bestErr) { bestErr = err; bearing = b; dist = Math.sqrt(dx * dx + dz * dz); }
        }
        aim.add((float) Math.min(90, bestErr));

        boolean high = false, locked = false;
        if (!Double.isNaN(bearing) && !Double.isNaN(prevBearing) && dist < 6 && dist > 0.8) {
            double angVel = Math.abs(PState.wrap((float) (bearing - prevBearing)));
            high = angVel >= 5;
            locked = high && bestErr <= 2.5;
        }
        prevBearing = bearing;
        pushAim(high, locked);
        if (aimHigh >= 16) {
            double ratio = aimLocked / (double) aimHigh;
            if (ratio >= Math.min(0.97, 0.85 * sens)) {
                float sev = (float) clamp(0.35 + (ratio - 0.85) * 3.5, 0.35, 0.9);
                flag(t, CheckType.AIM, sev, ratio * 100,
                    String.format("Crosshair locked on you %.0f%% of the time while you strafed fast", ratio * 100), op, 100);
            }
        }

        // snaps: 2-tick yaw change (players update every other tick)
        PState o2 = op(t - 2), m2 = me(t - 2);
        if (o2 != null && m2 != null) {
            double dyaw = Math.abs(PState.wrap(op.yw - o2.yw));
            double errNow = aimError(op, me), errBefore = aimError(o2, m2);
            if (dyaw >= 70 && errNow < 10 && errBefore > 45) snapPending.add(new double[]{t, dyaw, 0, 0});
            for (double[] sp : snapPending) {
                if (t > sp[0] && t <= sp[0] + 8 && dyaw >= 70 && errNow > 45) sp[3] = 1;
            }
        }
        for (double[] sp : snapPending) if (Math.abs(lastHitMe - sp[0]) <= 3) sp[2] = 1;
    }

    private void pushAim(boolean high, boolean locked) {
        aimWindow.addLast(new boolean[]{high, locked});
        if (high) aimHigh++;
        if (locked) aimLocked++;
        if (aimWindow.size() > 80) {
            boolean[] old = aimWindow.pollFirst();
            if (old[0]) aimHigh--;
            if (old[1]) aimLocked--;
        }
    }

    private void resolveSnaps(int t) {
        snapPending.removeIf(sp -> {
            if (t < sp[0] + 8) return false;
            if (sp[2] > 0) {
                int at = (int) sp[0];
                if (sp[3] > 0) flag(at, CheckType.KILLAURA, 0.7f, sp[1],
                    String.format("Snapped %.0f° onto you, hit, and snapped straight back", sp[1]), op(at), 10);
                else flag(at, CheckType.KILLAURA, 0.3f, sp[1],
                    String.format("Snapped %.0f° onto you right as they hit", sp[1]), op(at), 10);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ movement
    private void tickSpeed(int t, PState op) {
        PState prev = op(t - 1);
        boolean exempt = op == null || prev == null
            || op.has(PState.GLIDE | PState.VEHICLE | PState.FLUID | PState.CREATIVE)
            || t - opHurtTick < 30;
        double dh = 0;
        if (op != null && prev != null) {
            double dx = op.x - prev.x, dz = op.z - prev.z;
            dh = Math.sqrt(dx * dx + dz * dz);
            if (dh > 3) { exempt = true; opTeleportTick = t; }
        }
        if (exempt) {
            speedWindow.clear();
            speed.add(op == null ? Float.NaN : (float) Math.min(40, dh * 20));
            return;
        }
        speedWindow.addLast(dh);
        if (speedWindow.size() > 10) speedWindow.pollFirst();
        double avg = 0;
        for (double d : speedWindow) avg += d;
        avg /= speedWindow.size();
        speed.add((float) (avg * 20));
        if (speedWindow.size() == 10 && avg > 0.56 * sens) {
            float sev = (float) clamp(0.3 + (avg - 0.56) / 0.4, 0.3, 1.0);
            flag(t, CheckType.SPEED, sev, avg * 20, String.format("Moving %.1f blocks/s (sprint-jump is ~7)", avg * 20), op, 40);
        }
    }

    private void tickFlight(int t, PState op) {
        boolean air = op != null && !op.has(PState.GROUND | PState.SUPPORTED | PState.FLUID | PState.CLIMB
            | PState.GLIDE | PState.VEHICLE | PState.CREATIVE) && t - opHurtTick >= 40 && t - opTeleportTick >= 10;
        airRun = air ? airRun + 1 : 0;
        if (airRun < 14) return;
        PState a = op(t - 12), b = op(t - 10), c = op(t - 2);
        if (a == null || b == null || c == null) return;
        double vStart = (b.y - a.y) / 2, vEnd = (op.y - c.y) / 2, dv = vEnd - vStart;
        // gravity changes vertical speed by ~-0.8 blocks/tick over 10 ticks unless already at terminal speed
        if (dv > -0.25 && vEnd > -2.5) {
            float sev = (float) clamp(0.45 + (airRun - 14) / 40.0 * 0.45, 0.45, 0.9);
            flag(t, CheckType.FLIGHT, sev, airRun / 20.0,
                String.format("In the air %.1f s with no gravity (%s %.2f blocks)", airRun / 20.0,
                    op.y >= a.y ? "rose" : "fell", Math.abs(op.y - a.y)), op, 40);
        }
    }

    private void resolveVelocity(int t) {
        velocityPending.removeIf(p -> {
            int at = (Integer) p[0];
            if (t < at + 12) return false;
            PState p0 = (PState) p[1];
            double ux = (Double) p[2], uz = (Double) p[3];
            double max = -99;
            int seen = 0;
            for (int i = at + 1; i <= Math.min(at + 12, frames.size() - 1); i++) {
                PState o = op(i);
                if (o == null) continue;
                seen++;
                max = Math.max(max, (o.x - p0.x) * ux + (o.z - p0.z) * uz);
            }
            if (seen < 6) return true;
            kbChecked++;
            if (max < 0.12 * sens) {
                kbMissing++;
                flag(at, CheckType.VELOCITY, 0.2f, max, String.format("Moved only %.2f blocks back after your hit", Math.max(0, max)), p0, 0);
            }
            if (kbChecked >= 4 && kbMissing / (double) kbChecked >= 0.6) {
                flag(at, CheckType.VELOCITY, 0.8f, kbMissing,
                    String.format("Took no knockback on %d of %d hits", kbMissing, kbChecked), p0, 200);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ geometry
    /** Distance from a point to a player's hitbox. */
    public static double boxDistance(double x, double y, double z, PState p) {
        double hw = p.w / 2;
        double cx = clamp(x, p.x - hw, p.x + hw), cy = clamp(y, p.y, p.y + p.h), cz = clamp(z, p.z - hw, p.z + hw);
        double dx = x - cx, dy = y - cy, dz = z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double[] look(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /** Angle (degrees) between where {@code from} looks and the nearest part of {@code target}'s hitbox. */
    public static double aimError(PState from, PState target) {
        double ex = from.x, ey = from.eyeY(), ez = from.z;
        double[] l = look(from.yw, from.pt);
        double tcx = target.x, tcy = target.y + target.h / 2, tcz = target.z;
        double dist = Math.sqrt((tcx - ex) * (tcx - ex) + (tcy - ey) * (tcy - ey) + (tcz - ez) * (tcz - ez));
        double hw = target.w / 2;
        double px = clamp(ex + l[0] * dist, target.x - hw, target.x + hw);
        double py = clamp(ey + l[1] * dist, target.y, target.y + target.h);
        double pz = clamp(ez + l[2] * dist, target.z - hw, target.z + hw);
        return angle(l, px - ex, py - ey, pz - ez);
    }

    public static double angleTo(PState from, double x, double y, double z) {
        return angle(look(from.yw, from.pt), x - from.x, y - from.eyeY(), z - from.z);
    }

    private static double angle(double[] l, double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return 0;
        double dot = (l[0] * dx + l[1] * dy + l[2] * dz) / len;
        return Math.toDegrees(Math.acos(clamp(dot, -1, 1)));
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Growable float array; NaN = no value. */
    public static final class Series {
        private float[] data = new float[256];
        private int size = 0;

        void add(float v) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = v;
        }

        void set(int i, float v) { if (i >= 0 && i < size) data[i] = v; }

        public float get(int i) { return i >= 0 && i < size ? data[i] : Float.NaN; }

        public int size() { return size; }

        public float max() {
            float m = Float.NaN;
            for (int i = 0; i < size; i++) if (!Float.isNaN(data[i]) && (Float.isNaN(m) || data[i] > m)) m = data[i];
            return m;
        }
    }
}
