package dev.pvpmod.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * The counter coach: for each cheat, what it does to you, how to beat it with legit play, and a line built from
 * your own fight so the advice is about what actually happened.
 */
public final class Counter {
    private Counter() {}

    /** One-liner for the live island when a flag fires. */
    public static String shortTip(CheckType c) {
        return switch (c) {
            case REACH -> "Don't trade at 3+ blocks: get right up close or back right off";
            case KILLAURA -> "Getting behind them won't work: shield up and fight at range";
            case AIM -> "Strafing won't make them miss: win the spacing with W/S-taps";
            case AUTOCLICKER -> "Don't stay in long trades: break the combo with knockback";
            case VELOCITY -> "They won't get knocked away: go for damage, not combos";
            case SPEED -> "Don't chase them: hold your ground and make them come to you";
            case FLIGHT -> "Stay low and use a bow or crossbow to hit them in the air";
            case AUTO_TOTEM -> "One pop won't finish them: line up two big hits back to back";
            case CRYSTAL_AURA -> "Get out of open crystal range: hole up and keep the high ground";
            case WALL_HIT -> "Blocks won't save you: heal far away or with full cover";
        };
    }

    /** How to beat it. */
    public static List<String> tips(CheckType c) {
        return switch (c) {
            case REACH -> List.of(
                "Avoid the 3 to 4 block zone: they can hit you there and you can't hit them. Either get inside 2 blocks, where their reach doesn't matter, or stay right out of range.",
                "Use knockback to control spacing: sprint-reset (W-tap) after every hit so they get pushed back before they can swing.",
                "Damage from range: bows, crossbows, tridents, crystals and mace smashes all ignore melee reach.");
            case KILLAURA -> List.of(
                "Running past them or getting behind them doesn't stop the hits, because the aura hits all around them. Don't play for their back.",
                "Hold a shield: auras keep swinging into it. Lower it only to hit back.",
                "Auras still need you within about 3 to 4 blocks. Stay just outside, then go in for short bursts and back out, or use ranged damage.");
            case AIM -> List.of(
                "Strafing to make them miss won't work, because their aim locks onto you. Stop spending effort on dodging.",
                "Win on timing instead: W-tap and S-tap so your hit lands first, and jump-crit when they come in.",
                "Break their line: pillars, corners and height changes make lock-on aim swing wildly and lose you.");
            case AUTOCLICKER -> List.of(
                "Their clicks never slow down, so they win long hit trades. Keep exchanges short.",
                "Break combos with knockback: jump-reset or S-tap as you get hit, then re-engage on your own timing.",
                "On 1.9+ combat clicks don't stack damage: time full-strength hits off the attack indicator and you out-damage spam.");
            case VELOCITY -> List.of(
                "They won't fly off edges or into lava, so stop trying to combo them away. Focus on raw damage: crits, Sharpness, Strength, axe hits.",
                "Because they stay put, they're easy to trap: drop webs, obsidian or lava where they stand, and crystals land reliably.",
                "Don't let them pin you against a wall, because your knockback doesn't push them off you.");
            case SPEED -> List.of(
                "Never chase them in the open, you'll lose. Hold a good spot and make them come to you.",
                "Slow them down: cobwebs, slowness arrows and blocking paths with blocks take the speed away.",
                "Save pearls to escape or close distance; outrunning them on foot doesn't work.");
            case FLIGHT -> List.of(
                "Don't follow them up pillars or into the air: stay grounded where you can move freely.",
                "Use projectiles: bows, crossbows and tridents hit them while they hover.",
                "Fight under a roof or in tight areas where hovering gives them nothing.");
            case AUTO_TOTEM -> List.of(
                "Their totem comes back almost instantly, so a single pop won't kill them. You need two big hits back to back.",
                "Burst right after a pop: crystal plus axe, or a mace smash, inside the same second, before the new totem's effects help.",
                "Count their pops. When their totems run out the auto totem can't save them, so play for the long game.");
            case CRYSTAL_AURA -> List.of(
                "Don't stand in open crystal range: get into a hole (obsidian or bedrock around your feet).",
                "Keep the high ground: crystals placed below you hurt much less. Blast Protection helps a lot.",
                "Take away their placements: break their obsidian and put blocks where they'd place crystals.");
            case WALL_HIT -> List.of(
                "Thin walls don't protect you from them: heal and re-gear far away, or behind full blocks.",
                "Don't stand still behind cover, because they can still hit you there.",
                "Use the gap to reposition, not to rest.");
        };
    }

    /** A line built from your fight: what they did to you, in numbers. */
    public static String personal(CheckType c, FightRecord r, Analyzer a) {
        return switch (c) {
            case REACH -> {
                double maxFlag = 0;
                for (Flag f : a.flags) if (f.c == CheckType.REACH) maxFlag = Math.max(maxFlag, f.v);
                double[] z = zones(r, Math.max(3.2, maxFlag));
                yield String.format("They hit you from up to %.2f blocks. You spent %.0f%% of the fight between 3 and %.1f blocks from them: that's their free-hit zone.",
                    maxFlag, z[1] * 100, Math.max(3.2, maxFlag));
            }
            case KILLAURA -> String.format("%d of your hits taken came while they weren't looking at you.", a.flagCount(CheckType.KILLAURA));
            case AIM -> "Their crosshair stayed locked on you while you strafed fast. Dodging didn't make them miss.";
            case AUTOCLICKER -> String.format("They peaked at %.0f clicks per second.", a.peakCps);
            case VELOCITY -> {
                String last = "";
                for (Flag f : a.flags) if (f.c == CheckType.VELOCITY && f.d.startsWith("Took")) last = f.d + ".";
                yield last.isEmpty() ? a.flagCount(CheckType.VELOCITY) + " of your hits didn't push them back." : last;
            }
            case SPEED -> String.format("They reached %.1f blocks/s. Normal sprint-jumping is about 7.", a.speed.max());
            case FLIGHT -> "They stayed in the air with nothing under them.";
            case AUTO_TOTEM -> String.format("You popped them %d times and they had a new totem back almost instantly.", a.popsOp);
            case CRYSTAL_AURA -> String.format("They used %d crystals near you, broken faster than a human could react.", a.crystals);
            case WALL_HIT -> "They hit you while blocks were between you.";
        };
    }

    /** General coaching from your fight, shown whatever was flagged. */
    public static List<String> general(FightRecord r, Analyzer a) {
        List<String> out = new ArrayList<>();
        double[] z = zones(r, 3.6);
        int given = a.hitsGiven, taken = a.hitsTaken;
        if (given + taken > 0) {
            out.add(String.format("Hit trade: you landed %d hits and took %d (%s).", given, taken,
                given >= taken ? "you won the exchanges" : "they won the exchanges"));
        }
        out.add(String.format("Spacing: %.0f%% of the fight close (under 3 blocks), %.0f%% at the edge of reach (3 to 3.6), %.0f%% far away.",
            z[0] * 100, z[1] * 100, z[2] * 100));
        if (a.popsOp + a.popsMe > 0) out.add(String.format("Totems: you popped them %d times, they popped you %d times.", a.popsOp, a.popsMe));
        if (r.result.equals("LOSS") && taken > given) out.add("You lost the hit trade. Next time reset with a W-tap after each hit so they can't chain combos.");
        return out;
    }

    /** Share of ticks you were {&lt;3, 3..edge, &gt;edge} blocks from their eyes. */
    static double[] zones(FightRecord r, double edge) {
        int close = 0, mid = 0, far = 0;
        for (Frame f : r.frames) {
            if (f.op == null || f.op.has(PState.ABSENT) || f.me == null) continue;
            double d = Analyzer.boxDistance(f.op.x, f.op.eyeY(), f.op.z, f.me);
            if (d < 3.0) close++;
            else if (d <= edge) mid++;
            else far++;
        }
        double n = Math.max(1, close + mid + far);
        return new double[]{close / n, mid / n, far / n};
    }

    /** Plain-text evidence summary for server staff. */
    public static String staffReport(FightRecord r, Analyzer a) {
        StringBuilder b = new StringBuilder();
        b.append("Possible cheater: ").append(r.opName).append(" (").append(r.opUuid).append(")\n");
        if (!r.server.isEmpty()) b.append("Server: ").append(r.server).append("\n");
        b.append("Fight: ").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(r.start)))
            .append(", ").append(r.ticks() / 20).append(" s\n");
        b.append(String.format("Suspicion: %.0f%% (%s), from a client-side fight analyzer\n", a.score(), Analyzer.verdict(a.score())));
        for (CheckType c : CheckType.values()) {
            int n = a.flagCount(c);
            if (n == 0) continue;
            b.append("\n").append(c.title).append(" (").append(n).append(n == 1 ? " flag" : " flags").append("):\n");
            int shown = 0;
            for (Flag f : a.flags) {
                if (f.c != c || shown >= 5) continue;
                int s = f.t / 20;
                b.append(String.format("  %d:%02d  %s%n", s / 60, s % 60, f.d));
                shown++;
            }
        }
        return b.toString();
    }
}
