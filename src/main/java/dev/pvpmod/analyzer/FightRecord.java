package dev.pvpmod.analyzer;

import java.util.ArrayList;
import java.util.List;

/** A whole recorded fight. Saved as gzip JSON in .minecraft/pvpmod/fights. */
public class FightRecord {
    public int version = 1;
    public String id = "";
    public String opName = "?";
    public String opUuid = "";
    public String meName = "", meUuid = "";
    /** Signed "textures" profile properties, so skins still load after the players log off. */
    public String opTex = "", opSig = "", meTex = "", meSig = "";
    public String server = "";
    /** Fights recorded at the same time (2v2, FFA, getting teamed) share a group id. */
    public String groupId = "";
    /** The other fighters in the group, whose positions are stored per frame in {@link Frame#x}. */
    public List<Other> others = new ArrayList<>();
    /** The blocks around the fight for the 3D replay (see {@link WorldSnapshot}); null if not saved. */
    public int[] wb;
    public String dim = "";
    public long start;
    public int preroll;            // ticks recorded before the first hit
    public String result = "END";  // WIN, LOSS, LEFT, END
    public float sens = 1f;
    public List<Frame> frames = new ArrayList<>();
    public List<FightEvent> events = new ArrayList<>();

    public int ticks() { return frames.size(); }

    public FightMeta meta(Analyzer a) {
        FightMeta m = new FightMeta();
        m.id = id;
        m.opName = opName;
        m.opUuid = opUuid;
        m.opTex = opTex;
        m.opSig = opSig;
        m.server = server;
        m.groupId = groupId;
        m.start = start;
        m.ticks = ticks();
        m.result = result;
        m.score = a.score();
        m.flags = a.flags.size();
        m.hitsGiven = a.hitsGiven;
        m.hitsTaken = a.hitsTaken;
        List<CheckType> top = new ArrayList<>(List.of(CheckType.values()));
        top.removeIf(c -> a.checkScore(c) < 1);
        top.sort((x, y) -> Float.compare(a.checkScore(y) * y.weight, a.checkScore(x) * x.weight));
        for (int i = 0; i < Math.min(3, top.size()); i++) m.top.add(top.get(i).name());
        int n = a.sus.size();
        for (int i = 0; i < m.spark.length; i++) {
            int idx = n == 0 ? 0 : Math.min(n - 1, (int) ((i + 1) / (float) m.spark.length * n));
            m.spark[i] = n == 0 ? 0 : a.sus.get(idx);
        }
        return m;
    }

    /** Another fighter in a group fight. */
    public static class Other {
        public String uuid = "", name = "", tex = "", sig = "";

        public Other() {}

        public Other(String uuid, String name, String tex, String sig) {
            this.uuid = uuid; this.name = name; this.tex = tex; this.sig = sig;
        }
    }

    /** Small summary stored next to each fight, so the list opens instantly. */
    public static class FightMeta {
        public String id, opName, opUuid, server, result, opTex = "", opSig = "", groupId = "";
        public long start;
        public int ticks, flags, hitsGiven, hitsTaken;
        public float score;
        public List<String> top = new ArrayList<>();
        public float[] spark = new float[24];
    }
}
