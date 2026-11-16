package dev.pvpmod.analyzer;

/** Something that happened during a fight (a hit, a totem pop, a crystal...). */
public class FightEvent {
    public enum Kind {
        HIT_ME,        // opponent melee-hit me. b = no line of sight, d = 1 for a critical hit
        HIT_OP,        // I melee-hit the opponent. b = wall/hole behind them (no knockback possible)
        BLAST_ME,      // explosion / other damage to me caused by the opponent
        BLAST_OP,
        POP_ME, POP_OP,
        REFILL_OP,     // opponent has a totem in hand again after a pop. v = ticks since pop
        CRYSTAL_OP,    // crystal placed near the opponent. v = lifetime ticks, d = their aim error (deg) at spawn
        DEATH_ME, DEATH_OP
    }

    public int t;
    public Kind k;
    public double v, d;
    public double x, y, z;
    public boolean b;

    public FightEvent() {}

    public FightEvent(int t, Kind k) {
        this.t = t;
        this.k = k;
    }

    public FightEvent at(double x, double y, double z) {
        this.x = x; this.y = y; this.z = z;
        return this;
    }
}
