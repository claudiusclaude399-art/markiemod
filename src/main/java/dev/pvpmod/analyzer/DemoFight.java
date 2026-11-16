package dev.pvpmod.analyzer;

import java.util.Random;

/**
 * Builds a simulated 60 second fight against a cheater, so the analyzer can be tried without a real opponent
 * (/pvpmod demo or the button on the empty fight list). The cheater uses reach, an aimbot, an autoclicker,
 * auto totem, anti-knockback and a speed burst at different times.
 */
public final class DemoFight {
    private DemoFight() {}

    public static FightRecord create() {
        Random rnd = new Random(7);
        FightRecord r = new FightRecord();
        r.start = System.currentTimeMillis();
        r.id = "demo-" + r.start;
        r.opName = "Demo_Cheater";
        r.opUuid = "8667ba71-b85a-4004-af54-457a9734eed7";
        r.server = "Demo";
        r.dim = "minecraft:overworld";
        r.result = "LOSS";
        r.preroll = 20;

        double ox = 0, oz = 0, ang = 0;
        float meHp = 20, opHp = 20;
        int lastHitMe = -100, lastHitOp = -100, popRefill = -1;
        boolean opHasTotem = true;
        double kbx = 0, kbz = 0;
        double opSpeedBoost = 0;

        for (int t = 0; t < 1200; t++) {
            // me: circle-strafe around them, jumping now and then
            double speed = (t >= 360 && t < 640) ? 0.115 : 0.06 + 0.02 * Math.sin(t / 37.0);
            ang += speed * (Math.sin(t / 90.0) > -0.3 ? 1 : -1);
            double rad = 2.7 + 0.7 * Math.sin(t / 53.0);
            boolean cheatReach = (t >= 150 && t < 330) || (t >= 900 && t < 1000);
            if (cheatReach) rad += 1.3;

            // them: drift towards the centre, speed burst late in the fight
            if (t >= 1020 && t < 1060) opSpeedBoost = 0.72; else opSpeedBoost = 0;
            ox += Math.cos(t / 70.0) * 0.03 + opSpeedBoost + kbx;
            oz += Math.sin(t / 60.0) * 0.03 + kbz;
            kbx *= 0.55; kbz *= 0.55;
            if (opSpeedBoost == 0 && Math.abs(ox) > 6) ox *= 0.97;

            PState op = new PState();
            op.x = ox; op.z = oz; op.y = 64;
            op.f = PState.GROUND | PState.SPRINT;
            PState me = new PState();
            me.x = ox + Math.sin(ang) * rad;
            me.z = oz + Math.cos(ang) * rad;
            double jump = (t % 47) < 12 ? Math.sin((t % 47) / 12.0 * Math.PI) * 1.1 : 0;
            me.y = 64 + jump;
            me.f = jump > 0 ? PState.SPRINT : PState.GROUND | PState.SPRINT;

            // their aim: human lag + noise, or a perfect lock while the aimbot is on
            double dx = me.x - op.x, dz = me.z - op.z;
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            // the cheat aims at where its client sees you: ~4 ticks in the past once both pings are added
            PState seen = t >= 4 ? r.frames.get(t - 4).me : me;
            double seenBearing = Math.toDegrees(Math.atan2(-(seen.x - op.x), seen.z - op.z));
            boolean aimbot = t >= 360 && t < 640;
            float yaw = (float) (aimbot ? seenBearing : seenBearing - speed * 57.3 * (3 + rnd.nextFloat() * 6) + rnd.nextGaussian() * 7);
            if (t >= 812 && t < 826) yaw += 120;          // looking away... and still hitting (killaura)
            op.yw = Math.round(yaw / 1.40625f) * 1.40625f;
            op.pt = 8 + (float) rnd.nextGaussian() * 2;
            me.yw = (float) Math.toDegrees(Math.atan2(-(op.x - me.x), op.z - me.z));

            // clicks: autoclicker at a perfectly even 14 CPS between 300 and 620
            if (t >= 300 && t < 620) op.s = (t % 10 < 7 && t % 10 != 3) ? (t % 3 == 0 ? 2 : 1) : 1;
            else op.s = rnd.nextFloat() < 0.38f ? 1 : 0;
            if (t >= 300 && t < 620) op.s = ((t * 14) / 20) - (((t - 1) * 14) / 20);

            // totem in the offhand, refilled one tick after each pop
            op.m = "netherite_sword";
            op.o = opHasTotem ? "totem_of_undying" : "";
            me.m = "netherite_sword";
            me.o = "totem_of_undying";

            Frame f = new Frame(t, me, op, 45, 70);
            r.frames.add(f);

            // hits on me
            double dist = Analyzer.boxDistance(op.x, op.eyeY(), op.z, me);
            boolean noLook = t == 822;
            if ((t - lastHitMe >= 11 && dist < (cheatReach ? 4.2 : 3.0) && (t < 985 || t > 1075) && rnd.nextFloat() < 0.45f) || noLook) {
                lastHitMe = t;
                FightEvent e = new FightEvent(t, FightEvent.Kind.HIT_ME).at(op.x, op.y, op.z);
                r.events.add(e);
                meHp -= 1.3f;
                if (meHp < 4) meHp = 4;
            }
            // my hits on them; with anti-knockback (450..800) they don't move back
            if (t - lastHitOp >= 13 && dist < 3.0 && (t < 985 || t > 1075) && rnd.nextFloat() < 0.35f) {
                lastHitOp = t;
                r.events.add(new FightEvent(t, FightEvent.Kind.HIT_OP).at(op.x, op.y, op.z));
                opHp -= 1.6f;
                boolean antiKb = t >= 450 && t < 800;
                if (!antiKb) {
                    double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                    kbx = -dx / len * 0.42;
                    kbz = -dz / len * 0.42;
                }
            }
            // totem pops
            if (opHp <= 2 && opHasTotem) {
                r.events.add(new FightEvent(t, FightEvent.Kind.POP_OP).at(op.x, op.y, op.z));
                opHasTotem = false;
                popRefill = t + 2;
                opHp = 12;
            } else if (t == popRefill) {
                opHasTotem = true;
                FightEvent e = new FightEvent(t, FightEvent.Kind.REFILL_OP).at(op.x, op.y, op.z);
                e.v = 2;
                r.events.add(e);
            }
            op.hp = Math.max(1, opHp);
            me.hp = meHp;
            if (t > 1100) meHp = Math.max(0, meHp - 0.2f);
        }
        r.events.add(new FightEvent(1199, FightEvent.Kind.DEATH_ME).at(r.frames.get(1199).me.x, 64, r.frames.get(1199).me.z));
        return r;
    }

    /** Create, analyse and save a demo fight. */
    public static FightRecord.FightMeta saveDemo() {
        FightRecord r = create();
        var mc = net.minecraft.client.MinecraftClient.getInstance();
        var me = mc.getGameProfile();
        r.meUuid = me.id().toString();
        r.meName = me.name();
        for (var prop : me.properties().get("textures")) {
            r.meTex = prop.value();
            r.meSig = prop.signature() == null ? "" : prop.signature();
        }
        Analyzer a = Analyzer.run(r);
        FightRecord.FightMeta m = r.meta(a);
        FightStore.save(r, m);
        return m;
    }
}
