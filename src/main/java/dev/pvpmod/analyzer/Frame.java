package dev.pvpmod.analyzer;

/** Both players in one tick, plus pings for lag compensation. */
public class Frame {
    public int t;
    public PState me, op;
    public int mp, opg;   // my ping, opponent ping (ms)

    public Frame() {}

    public Frame(int t, PState me, PState op, int mp, int opg) {
        this.t = t;
        this.me = me;
        this.op = op;
        this.mp = mp;
        this.opg = opg;
    }
}
