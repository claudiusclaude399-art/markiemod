package dev.pvpmod.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every check over a fight. Used live while recording (step() each tick) and again when a saved fight is
 * opened, so results are always reproducible from the recording alone.
 *
 * Everything here works from what a client can see of another player: positions and rotations from the server,
 * arm swings, damage packets (with the attacker), equipment and totem pops. The analyzer is built to avoid false
 * flags:
 * <ul>
 *   <li><b>Ping is always read in their favour.</b> Distances and angles use the highest ping seen recently (yours
 *   plus theirs), so whatever they saw on their screen when they clicked is covered. Reaction times use their
 *   <i>lowest</i> recent ping, so a stale tab-list ping can't make them look faster than they were.</li>
 *   <li><b>Lag spikes are ignored.</b> When their position freezes and then jumps (packet loss, a lag spike) or they
 *   vanish, everything around that moment is skipped.</li>
 *   <li><b>One odd moment is not enough.</b> Each check needs repeated evidence before its score climbs, and single
 *   borderline events only count a little.</li>
 *   <li>Checks are evaluated {@value #DELAY} ticks late, so each moment can be judged with what happened next.</li>
 * </ul>
 */
public final class Analyzer {
    public static final double REACH_LIMIT = 3.0;
    /** Ticks of look-ahead each moment gets before it is judged. */
    static final int DELAY = 20;

    private final List<Frame> frames;
    private final List<FightEvent> events;
    /** 1 = normal, &lt;1 stricter, &gt;1 more relaxed. */
    private final float sens;

    public final Series reach = new Series(), cps = new Series(), aim = new Series(), speed = new Series(),
        myHp = new Series(), opHp = new Series(), sus = new Series();
    public final List<Flag> flags = new ArrayList<>();
    private final Map<CheckType, Double> keep = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Integer> counts = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Integer> lastFlag = new EnumMap<>(CheckType.class);

    // stats
    public int hitsGiven, hitsTaken, popsOp, popsMe, crystals, lagTicks;
    public double maxReach, reachSum;
    public int reachCount;
    public float peakCps;

    private int observed = 0, evaluated = 0, eventCursor = 0;
    private int opHurtTick = -1000, opTeleportTick = -1000, opLaunchTick = -1000, opGlideTick = -1000, opTridentTick = -1000;

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
        if (sev < 0.05f) return;
        lastFlag.put(c, t);
        Flag f = new Flag(t, c, sev, v, detail, where);
        flags.add(f);
        keep.put(c, keep.getOrDefault(c, 1.0) * (1.0 - 0.5 * f.sev));
        counts.merge(c, 1, Integer::sum);
    }

    /** 0..100 score for one check. A single flag only counts for about half: one odd moment is not proof. */
    public float checkScore(CheckType c) {
        int n = counts.getOrDefault(c, 0);
        double confidence = n >= 3 ? 1.0 : n == 2 ? 0.8 : 0.55;
        return (float) (100.0 * (1.0 - keep.getOrDefault(c, 1.0)) * confidence);
    }

    /** 0..100 overall suspicion. */
    public float score() {
        double clean = 1.0;
        for (CheckType c : CheckType.values()) clean *= 1.0 - (checkScore(c) / 100.0) * c.weight;
        return (float) (100.0 * (1.0 - clean));
    }

    public int flagCount(CheckType c) { return counts.getOrDefault(c, 0); }

    public static String verdict(float score) {
        if (score < 20) return "Looks legit";
        if (score < 45) return "Minor anomalies";
        if (score < 75) return "Suspicious";
        return "Likely cheating";
    }

    // ------------------------------------------------------------------ driving
    public void step() {
        while (observed < frames.size()) observe(observed++);
        while (evaluated < frames.size() - DELAY) evaluate(evaluated++);
    }

    public void finish() {
        while (observed < frames.size()) observe(observed++);
        while (evaluated < frames.size()) evaluate(evaluated++);
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

    // ------------------------------------------------------------------ lag & ping
    private final BitSet lag = new BitSet();
    private int stall = 0;

    /** Watches their position stream for freezes followed by jumps (lag spikes / packet loss). */
    private void observe(int i) {
        PState o = op(i), p = op(i - 1);
        if (o == null) {
            markLag(i - 5, i + 15);
            stall = 0;
            return;
        }
        if (p != null && o.x == p.x && o.y == p.y && o.z == p.z) {
            stall++;
            // nobody hangs perfectly still in mid-air: their packets have stopped arriving
            if (stall >= 3 && !o.has(PState.GROUND | PState.SUPPORTED | PState.FLUID | PState.CLIMB)) markLag(i - stall - 5, i + 15);
        } else {
            if (stall >= 3 && p != null) {
                double dx = o.x - p.x, dy = o.y - p.y, dz = o.z - p.z;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > 0.45) markLag(i - stall - 5, i + 15);
            }
            stall = 0;
        }
        // a sudden ping spike means a burst of delayed packets is coming
        Frame f = fr(i), f0 = fr(i - 40);
        if (f != null && f0 != null && f.mp + f.opg > (f0.mp + f0.opg) * 1.6 + 60) markLag(i - 20, i + 20);
    }

    private void markLag(int from, int to) { lag.set(Math.max(0, from), Math.max(0, to + 1)); }

    private boolean laggy(int from, int to) {
        int n = lag.nextSetBit(Math.max(0, from));
        return n >= 0 && n <= to;
    }

    /** Highest combined ping (ms) around t: used for distances and angles, the lenient direction. */
    private int pingMax(int t) {
        int m = 0;
        for (int i = Math.max(0, t - 60); i <= Math.min(frames.size() - 1, t + 10); i++) {
            Frame f = frames.get(i);
            m = Math.max(m, f.mp + f.opg);
        }
        return m;
    }

    /** Lowest opponent ping (ms) over the last 10 s: used for reaction times, the lenient direction. */
    private int opPingMin(int t) {
        int m = Integer.MAX_VALUE;
        for (int i = Math.max(0, t - 200); i <= Math.min(frames.size() - 1, t); i++) m = Math.min(m, frames.get(i).opg);
        return m == Integer.MAX_VALUE ? 0 : m;
    }

    private int opPingMax(int t) {
        int m = 0;
        for (int i = Math.max(0, t - 60); i <= Math.min(frames.size() - 1, t + 10); i++) m = Math.max(m, frames.get(i).opg);
        return m;
    }

    /** Ticks of history to search so whatever they saw at their ping is covered (+20% jitter, + interpolation). */
    private int lagWindow(int t) { return clamp((int) Math.ceil(pingMax(t) * 1.2 / 50.0) + 4, 4, 30); }

    /** High ping makes every measurement fuzzier, so evidence counts for less. */
    private float pingFactor(int t) {
        int p = pingMax(t);
        return p > 400 ? 0.5f : p > 250 ? 0.7f : 1f;
    }

    private static String pingNote(int ms) { return ms > 0 ? ", " + ms + " ms ping allowed for" : ""; }

    // ------------------------------------------------------------------ per tick
    private final ArrayDeque<Integer> swingWindow = new ArrayDeque<>();
    private int swingSum = 0, highCpsRun = 0;
    private final ArrayDeque<Integer> cpsSamples = new ArrayDeque<>();
    private final ArrayDeque<boolean[]> aimWindow = new ArrayDeque<>();
    private int aimHigh = 0, aimLocked = 0;
    private final ArrayDeque<Double> speedWindow = new ArrayDeque<>();
    private int speedRun = 0, airRun = 0;

    private void evaluate(int t) {
        Frame f = frames.get(t);
        PState me = f.me, op = op(t);
        myHp.add(me == null ? Float.NaN : me.hp + me.ab);
        opHp.add(op == null ? Float.NaN : op.hp + op.ab);
        reach.add(Float.NaN);
        if (lag.get(t)) lagTicks++;
        trackMovementContext(t, op);

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

    /** Remembers things that legitimately move players fast: elytra, tridents, wind charges / launches. */
    private void trackMovementContext(int t, PState op) {
        if (op == null) return;
        if (op.has(PState.GLIDE)) opGlideTick = t;
        if (op.has(PState.USING) && ("trident".equals(op.m) || "trident".equals(op.o))) opTridentTick = t;
        PState o2 = op(t - 2);
        if (o2 != null && Math.abs(op.y - o2.y) / 2 > 0.6) opLaunchTick = t;
        if ("wind_charge".equals(op.m) || "wind_charge".equals(op.o) || "mace".equals(op.m)) {
            if (!op.has(PState.GROUND)) opLaunchTick = t;
        }
    }

    // ------------------------------------------------------------------ events
    private final List<int[]> pops = new ArrayList<>();          // {tick}
    private int fastRefills = 0, noLookHits = 0;
    private final ArrayDeque<double[]> crystalWindow = new ArrayDeque<>();   // {tick, reactionMs}
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
                    && !op.has(PState.FLUID | PState.VEHICLE | PState.GLIDE | PState.CLIMB | PState.CREATIVE)) {
                    double dx = op.x - me.x, dz = op.z - me.z, len = Math.sqrt(dx * dx + dz * dz);
                    if (len > 0.05) velocityPending.add(new Object[]{t, op.copy(), dx / len, dz / len});
                }
            }
            case BLAST_OP -> opHurtTick = t;
            case POP_OP -> {
                popsOp++;
                opHurtTick = t;
                pops.add(new int[]{t});
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
        PState op = op(t);
        if (op == null || op.has(PState.CREATIVE)) return;
        boolean lagged = laggy(t - 10, t + 5);
        int L = lagWindow(t), ping = pingMax(t);
        float pf = pingFactor(t);

        // reach: they clicked on what their screen showed, which can be your position up to (ping + jitter) ago.
        // Take the closest you were to them over that whole window.
        double best = Double.MAX_VALUE;
        for (int i = Math.max(0, t - L); i <= t; i++) {
            PState m = me(i);
            if (m == null) continue;
            for (int j = Math.max(0, t - 3); j <= Math.min(frames.size() - 1, t + 2); j++) {
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
            double limit = REACH_LIMIT + 0.2 * sens;
            if (best > limit && !lagged) {
                float sev = (float) clamp((best - limit) / 0.8, 0.1, 1.0) * pf;
                flag(t, CheckType.REACH, sev, best,
                    String.format("Hit you from %.2f blocks (limit %.1f%s)", best, REACH_LIMIT, pingNote(ping)), op, 0);
            }
        }

        // no-look hit: not looking anywhere near you at any point they could have clicked
        double minErr = 180;
        for (int j = Math.max(0, t - 4); j <= Math.min(frames.size() - 1, t + 2); j++) {
            PState o = op(j);
            if (o == null) continue;
            for (int i = Math.max(0, t - L); i <= t; i++) {
                PState m = me(i);
                if (m != null) minErr = Math.min(minErr, aimError(o, m));
            }
        }
        if (!lagged && minErr > 75 * sens && minErr < 180) {
            noLookHits++;
            float sev = (noLookHits >= 2 ? 0.75f : 0.35f) * pf;
            flag(t, CheckType.KILLAURA, sev, minErr, String.format("Hit you while looking %.0f° away%s", minErr, pingNote(ping)), op, 0);
        }

        if (e.b && !lagged) flag(t, CheckType.WALL_HIT, 0.3f * pf, 0, "Hit you with solid blocks in the way", op, 10);
    }

    private void onRefill(int t, FightEvent e) {
        if (pops.isEmpty()) return;
        int pop = pops.remove(pops.size() - 1)[0];
        if (laggy(pop - 5, t + 5)) return;
        int ticks = (int) e.v;
        PState op = op(t);
        if (ticks == 0) {
            fastRefills++;
            flag(t, CheckType.AUTO_TOTEM, 0.4f, 0, "Totem back in hand in the same server tick as the pop", op, 0);
        } else {
            // their reaction = what we saw - their round trip - one server tick. Lowest recent ping = benefit of the doubt.
            int ping = opPingMin(pop);
            double reaction = ticks * 50.0 - ping - 50.0;
            if (reaction < 40 / sens) {
                fastRefills++;
                flag(t, CheckType.AUTO_TOTEM, 0.6f, reaction, String.format("New totem %d tick%s after the pop (~%.0f ms reaction after their %d ms ping)",
                    ticks, ticks == 1 ? "" : "s", Math.max(0, reaction), ping), op, 0);
            } else if (reaction < 90 / sens) {
                fastRefills++;
                flag(t, CheckType.AUTO_TOTEM, 0.25f, reaction, String.format("Totem refilled ~%.0f ms after the pop (after their %d ms ping)", reaction, ping), op, 0);
            }
        }
        if (fastRefills >= 3) {
            flag(t, CheckType.AUTO_TOTEM, 0.85f, fastRefills, fastRefills + " inhumanly fast totem refills this fight", op, 400);
        }
    }

    private void onCrystal(int t, FightEvent e) {
        crystals++;
        int spawn = t - (int) e.v;
        PState op = op(t);
        if (laggy(spawn - 5, t + 5)) return;
        // where were they looking when they placed it? (best over their rotations around the placement)
        double err = 180;
        for (int j = Math.max(0, spawn - 6); j <= Math.min(frames.size() - 1, spawn + 2); j++) {
            PState o = op(j);
            if (o != null) err = Math.min(err, angleTo(o, e.x, e.y, e.z));
        }
        if (err > 70 * sens && err < 180) {
            flag(t, CheckType.CRYSTAL_AURA, 0.3f, err, String.format("Placed a crystal %.0f° away from where they looked", err), op, 20);
        }
        // break speed: how long the crystal lived, minus their ping (they can't hit it before they see it)
        double reaction = e.v * 50.0 - opPingMin(spawn);
        crystalWindow.addLast(new double[]{t, reaction});
        while (!crystalWindow.isEmpty() && t - crystalWindow.peekFirst()[0] > 100) crystalWindow.pollFirst();
        if (crystalWindow.size() >= 30) {
            double[] r = crystalWindow.stream().mapToDouble(c -> c[1]).sorted().toArray();
            double median = r[r.length / 2];
            if (median <= 25 / sens) {
                flag(t, CheckType.CRYSTAL_AURA, 0.6f, median,
                    String.format("%d crystals in 5 s, broken ~%.0f ms after they could see them", crystalWindow.size(), Math.max(0, median)), op, 100);
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
        boolean lagged = laggy(t - 20, t);   // delayed packets arrive in bursts
        if (!lagged) peakCps = Math.max(peakCps, c);

        // drag/butterfly clicking legitimately reaches 20+ CPS, so only extreme rates count
        if (c >= 30 * sens && !lagged) highCpsRun++;
        else highCpsRun = 0;
        if (highCpsRun >= 40) {
            float sev = c >= 40 * sens ? 0.7f : 0.4f;
            flag(t, CheckType.AUTOCLICKER, sev, c, String.format("%.0f CPS for over 2 seconds", c), op, 100);
        }

        if (t % 20 == 19) {
            if (lagged) cpsSamples.clear();
            else cpsSamples.addLast(swingSum);
            if (cpsSamples.size() > 10) cpsSamples.pollFirst();
            if (cpsSamples.size() == 10) {
                double mean = 0, var = 0, min = 99;
                for (int v : cpsSamples) { mean += v; min = Math.min(min, v); }
                mean /= 10;
                for (int v : cpsSamples) var += (v - mean) * (v - mean);
                double sd = Math.sqrt(var / 10);
                if (min >= 10 && sd < 0.35 / sens) {
                    flag(t, CheckType.AUTOCLICKER, 0.3f, mean,
                        String.format("Clicked %.1f CPS with almost no variation for 10 s (sd %.2f)", mean, sd), op, 200);
                }
            }
        }
    }

    // ------------------------------------------------------------------ aim
    private double prevBearing = Double.NaN;
    private final List<double[]> snapPending = new ArrayList<>();   // {tick, yawDelta, hit(0/1), back(0/1), yawBefore}

    private void tickAim(int t, Frame f, PState op) {
        PState me = f.me;
        if (op == null || me == null || laggy(t - 5, t + 5)) {
            aim.add(op == null || me == null ? Float.NaN : (float) Math.min(90, Math.abs(PState.wrap((float) (op.yw - bearing(op, me))))));
            prevBearing = Double.NaN;
            pushAim(false, false);
            return;
        }
        // their rotation reaches us after both pings; compare it with where you were that long ago
        int lagT = clamp(Math.round((f.mp + f.opg) / 50f) + 2, 0, 14);
        double bestErr = 180, bearing = Double.NaN, dist = 99;
        for (int d = -1; d <= 1; d++) {
            PState m = me(t - lagT + d);
            if (m == null) continue;
            double b = bearing(op, m);
            double err = Math.abs(PState.wrap((float) (op.yw - b)));
            if (err < bestErr) {
                bestErr = err;
                bearing = b;
                dist = Math.sqrt((m.x - op.x) * (m.x - op.x) + (m.z - op.z) * (m.z - op.z));
            }
        }
        aim.add((float) Math.min(90, bestErr));

        boolean high = false, locked = false;
        if (!Double.isNaN(bearing) && !Double.isNaN(prevBearing) && dist < 6 && dist > 1.2) {
            double angVel = Math.abs(PState.wrap((float) (bearing - prevBearing)));
            high = angVel >= 6;
            locked = high && bestErr <= 2.0;
        }
        prevBearing = bearing;
        pushAim(high, locked);
        if (aimHigh >= 24) {
            double ratio = aimLocked / (double) aimHigh;
            if (ratio >= Math.min(0.97, 0.9 * sens)) {
                float sev = (float) clamp(0.35 + (ratio - 0.9) * 4, 0.35, 0.85) * pingFactor(t);
                flag(t, CheckType.AIM, sev, ratio * 100,
                    String.format("Crosshair locked on you %.0f%% of the time while you strafed fast", ratio * 100), op, 100);
            }
        }

        // snap onto you, hit, snap straight back (2-tick deltas: players update every other tick)
        PState o2 = op(t - 2), m2 = me(t - 2);
        if (o2 != null && m2 != null) {
            double dyaw = Math.abs(PState.wrap(op.yw - o2.yw));
            double errNow = aimError(op, me), errBefore = aimError(o2, m2);
            if (dyaw >= 70 && errNow < 10 && errBefore > 45) snapPending.add(new double[]{t, dyaw, 0, 0, o2.yw});
            for (double[] sp : snapPending) {
                // back = their aim returns to exactly where it was before the snap, away from you
                if (t > sp[0] + 1 && t <= sp[0] + 8 && errNow > 45 && Math.abs(PState.wrap((float) (op.yw - sp[4]))) < 20) sp[3] = 1;
            }
        }
        for (double[] sp : snapPending) if (Math.abs(lastHitMe - sp[0]) <= 3) sp[2] = 1;
    }

    private static double bearing(PState from, PState to) {
        return Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
    }

    private void pushAim(boolean high, boolean locked) {
        aimWindow.addLast(new boolean[]{high, locked});
        if (high) aimHigh++;
        if (locked) aimLocked++;
        if (aimWindow.size() > 100) {
            boolean[] old = aimWindow.pollFirst();
            if (old[0]) aimHigh--;
            if (old[1]) aimLocked--;
        }
    }

    private void resolveSnaps(int t) {
        snapPending.removeIf(sp -> {
            if (t < sp[0] + 8) return false;
            int at = (int) sp[0];
            // a flick onto someone to hit them is normal; only the snap back right after is suspicious
            if (sp[2] > 0 && sp[3] > 0 && !laggy(at - 5, at + 10)) {
                flag(at, CheckType.KILLAURA, 0.55f * pingFactor(at), sp[1],
                    String.format("Snapped %.0f° onto you, hit, and snapped straight back", sp[1]), op(at), 10);
            }
            return true;
        });
    }

    // ------------------------------------------------------------------ movement
    private void tickSpeed(int t, PState op) {
        PState prev = op(t - 1), back = op(t - 6);
        if (op != null && back != null) {
            double dx = op.x - back.x, dz = op.z - back.z;
            double v = Math.sqrt(dx * dx + dz * dz) / 6.0;
            speed.add(v > 3 ? Float.NaN : (float) (v * 20));
        } else {
            speed.add(Float.NaN);
        }
        boolean exempt = op == null || prev == null || laggy(t - 10, t + 10)
            || op.has(PState.GLIDE | PState.VEHICLE | PState.FLUID | PState.CREATIVE | PState.SLIPPERY)
            || t - opHurtTick < 30 || t - opGlideTick < 60 || t - opTridentTick < 40 || t - opLaunchTick < 40;
        if (op != null && prev != null) {
            double dx = op.x - prev.x, dz = op.z - prev.z;
            if (Math.sqrt(dx * dx + dz * dz) > 3) { exempt = true; opTeleportTick = t; }
        }
        if (exempt) {
            speedWindow.clear();
            speedRun = 0;
            return;
        }
        double dx = op.x - prev.x, dz = op.z - prev.z;
        speedWindow.addLast(Math.sqrt(dx * dx + dz * dz));
        if (speedWindow.size() > 10) speedWindow.pollFirst();
        double avg = 0;
        for (double d : speedWindow) avg += d;
        avg /= speedWindow.size();
        // sprint-jumping with Speed II peaks around 9 b/s; require well above that for a whole second
        if (speedWindow.size() == 10 && avg > 0.65 * sens) speedRun++;
        else speedRun = 0;
        if (speedRun >= 10) {
            float sev = (float) clamp(0.3 + (avg - 0.65) / 0.4, 0.3, 1.0);
            flag(t, CheckType.SPEED, sev, avg * 20, String.format("Moving %.1f blocks/s for over a second (sprint-jump is ~7)", avg * 20), op, 40);
        }
    }

    private void tickFlight(int t, PState op) {
        boolean air = op != null && !op.has(PState.GROUND | PState.SUPPORTED | PState.FLUID | PState.CLIMB
            | PState.GLIDE | PState.VEHICLE | PState.CREATIVE)
            && t - opHurtTick >= 40 && t - opTeleportTick >= 10 && t - opGlideTick >= 40 && t - opTridentTick >= 40
            && !laggy(t - 15, t + 10);
        airRun = air ? airRun + 1 : 0;
        if (airRun < 20) return;
        PState a = op(t - 12), b = op(t - 10), c = op(t - 2);
        if (a == null || b == null || c == null) return;
        int moved = 0;
        for (int i = t - 11; i <= t; i++) {
            PState p0 = op(i - 1), p1 = op(i);
            if (p0 != null && p1 != null && (p0.x != p1.x || p0.y != p1.y || p0.z != p1.z)) moved++;
        }
        if (moved < 5) return;   // a frozen position is lag, not flight
        double vStart = (b.y - a.y) / 2, vEnd = (op.y - c.y) / 2, dv = vEnd - vStart;
        // gravity changes vertical speed by ~-0.8 b/t over 10 ticks; slow falling still sinks faster than 0.05 b/t
        if (dv > -0.2 && vEnd >= -0.05) {
            float sev = (float) clamp(0.45 + (airRun - 20) / 40.0 * 0.4, 0.45, 0.85);
            flag(t, CheckType.FLIGHT, sev, airRun / 20.0,
                String.format("In the air %.1f s with no gravity (%s %.2f blocks)", airRun / 20.0,
                    op.y >= a.y ? "rose" : "fell", Math.abs(op.y - a.y)), op, 40);
        }
    }

    private void resolveVelocity(int t) {
        velocityPending.removeIf(p -> {
            int at = (Integer) p[0];
            // knockback reaches their client after half their ping, and their new position comes back after the rest
            int window = clamp(10 + (int) Math.ceil(opPingMax(at) * 1.2 / 50.0), 12, 40);
            if (t < at + window) return false;
            if (laggy(at - 5, at + window)) return true;
            PState p0 = (PState) p[1];
            double ux = (Double) p[2], uz = (Double) p[3];
            double maxH = -99, maxUp = -99;
            int seen = 0;
            for (int i = at + 1; i <= Math.min(at + window, frames.size() - 1); i++) {
                PState o = op(i);
                if (o == null) continue;
                seen++;
                maxH = Math.max(maxH, (o.x - p0.x) * ux + (o.z - p0.z) * uz);
                maxUp = Math.max(maxUp, o.y - p0.y);
            }
            if (seen < window / 2) return true;
            kbChecked++;
            // knockback also lifts a grounded player; holding W or netherite can cut the push back but not both
            boolean noHorizontal = maxH < 0.05 * sens;
            boolean noVertical = !p0.has(PState.GROUND) || maxUp < 0.06 * sens;
            if (noHorizontal && noVertical) {
                kbMissing++;
                flag(at, CheckType.VELOCITY, 0.1f, maxH,
                    String.format("Moved %.2f blocks back%s after your hit", Math.max(0, maxH), p0.has(PState.GROUND) ? " and didn't lift" : ""), p0, 0);
            }
            if (kbChecked >= 5 && kbMissing / (double) kbChecked >= 0.7) {
                flag(at, CheckType.VELOCITY, 0.75f, kbMissing,
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
