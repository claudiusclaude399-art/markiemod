package dev.markie.lyrics.render;

import dev.markie.lyrics.config.LyricsConfig;
import dev.markie.lyrics.gui.Fonts;
import dev.markie.lyrics.spotify.NowPlaying;
import dev.markie.lyrics.spotify.SpotifyClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;

/** "Now playing" card with the song title and artist that appears in the sky when a new song starts. */
public final class TitleCard {
    private static final long DURATION = 4600;
    private static final float RAD = (float) (Math.PI / 180.0);
    private static final StyleSpriteSource SMALL = new StyleSpriteSource.Font(Identifier.of("markielyrics", "ui"));

    private static String lastId;
    private static long startAt = -1;
    private static float yaw, pitch;
    private static Text title, artist;

    private TitleCard() {}

    /** 0..1 visibility, and checks for a new song. */
    private static float alpha() {
        LyricsConfig c = LyricsConfig.get();
        NowPlaying np = SpotifyClient.current();
        if (np == null) { lastId = null; return 0; }
        if (!np.id().equals(lastId)) {
            lastId = np.id();
            startAt = System.currentTimeMillis();
            Camera cam = MinecraftClient.getInstance().gameRenderer.getCamera();
            yaw = cam.getYaw();
            pitch = cam.getPitch();
            title = Text.literal(np.title().replaceAll("\\s*\\(preview\\)$", "")).setStyle(Style.EMPTY.withFont(Fonts.current()));
            artist = Text.literal(np.artist()).setStyle(Style.EMPTY.withFont(SMALL));
        }
        if (!c.titleCard || !c.showLyrics || startAt < 0) return 0;
        long t = System.currentTimeMillis() - startAt;
        if (t < 0 || t > DURATION) return 0;
        float in = LyricsAnimator.clamp01(t / 700f), out = LyricsAnimator.clamp01((DURATION - t) / 900f);
        // step aside once the singing starts, so it never fights with the first line
        LyricsAnimator.Shown first = LyricsAnimator.active();
        float yield = first == null ? 1f : 1f - LyricsAnimator.clamp01((System.currentTimeMillis() - first.appearReal) / 600f);
        return LyricsAnimator.easeOutCubic(in) * out * yield;
    }

    private static float progress() {
        return LyricsAnimator.clamp01((System.currentTimeMillis() - startAt) / (float) DURATION);
    }

    public static void renderWorld(MinecraftClient mc, Camera camera, MatrixStack ms, VertexConsumerProvider consumers) {
        LyricsConfig c = LyricsConfig.get();
        if (c.where == LyricsConfig.Where.SCREEN) return;
        float a = alpha();
        if (a <= 0.01f) return;
        boolean sky = c.where == LyricsConfig.Where.SKY;
        float p = progress();
        float dist = sky ? Math.min(c.skyDistance * 0.85f, Math.max(16f, mc.options.getClampedViewDistance() * 16f * 0.5f)) : c.floatDistance;
        float pt = sky ? -Math.max(5f, c.skyHeight - 7f) : pitch * 0.6f - c.floatHeight;
        float baseDist = dist;
        if (c.cinematicMotion) dist *= 1.08f - 0.12f * p;    // drifts gently towards you
        Vec3d rel = Vec3d.fromPolar(pt, yaw).multiply(dist);
        double horiz = Math.sqrt(rel.x * rel.x + rel.z * rel.z);
        float faceYaw = (float) (Math.atan2(-rel.x, rel.z) / RAD);
        float facePitch = (float) (-Math.atan2(rel.y, horiz) / RAD);
        float scale = baseDist * (sky ? 0.0068f : 0.0056f) * c.size;
        ms.push();
        ms.translate(rel.x, rel.y, rel.z);
        ms.multiply(new Quaternionf().rotationYXZ((float) Math.PI - faceYaw * RAD, -facePitch * RAD, 0));
        ms.scale(scale, -scale, scale);
        draw(new Canvas.World(ms, consumers, c.throughWalls, LightmapTextureManager.MAX_LIGHT_COORDINATE), a, p);
        ms.pop();
    }

    public static void renderScreen(DrawContext ctx) {
        float a = alpha();
        if (a <= 0.01f) return;
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(ctx.getScaledWindowWidth() / 2f, ctx.getScaledWindowHeight() * 0.24f);
        m.scale(1.5f, 1.5f);
        draw(new Canvas.Screen(ctx), a, progress());
        m.popMatrix();
    }

    private static void draw(Canvas cv, float a, float p) {
        LyricsConfig c = LyricsConfig.get();
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        float rise = (1f - LyricsAnimator.easeOutCubic(LyricsAnimator.clamp01(p * 6f))) * 5f;
        cv.push();
        cv.translate(0, rise);

        // tiny spaced-out label, letters drawing together as it appears
        String label = "NOW PLAYING";
        float track = 1.2f + (1f - LyricsAnimator.easeOutCubic(LyricsAnimator.clamp01(p * 4f))) * 2.5f;
        cv.push();
        cv.translate(0, -17f);
        cv.scale(0.6f);
        float lw = 0;
        Text[] chars = new Text[label.length()];
        for (int i = 0; i < label.length(); i++) {
            chars[i] = Text.literal(String.valueOf(label.charAt(i))).setStyle(Style.EMPTY.withFont(SMALL));
            lw += tr.getWidth(chars[i]) + track;
        }
        float lx = -lw / 2f;
        for (Text ch : chars) {
            cv.text(ch, lx, -4f, LyricsAnimator.argb(a * 0.75f, LyricsAnimator.lerpRgb(Colors.colorB(), 0xFFFFFF, 0.3f)), false);
            lx += tr.getWidth(ch) + track;
        }
        cv.pop();

        // title, in the lyric font with the same 3D edge
        cv.push();
        cv.scale(1.3f);
        float tw = tr.getWidth(title);
        LineRenderer.drawGlyph(cv, c, title, -tw / 2f, -4.5f, a, Colors.colorA(), Colors.glow());
        cv.pop();

        // artist with soft lines either side
        cv.push();
        cv.translate(0, 11f);
        cv.scale(0.8f);
        float aw = tr.getWidth(artist);
        cv.text(artist, -aw / 2f, -4f, LyricsAnimator.argb(a * 0.85f, Colors.colorB()), false);
        float lineLen = 22f * LyricsAnimator.easeOutCubic(LyricsAnimator.clamp01(p * 3f));
        int lc = LyricsAnimator.argb(a * 0.7f, Colors.colorB());
        cv.sprite(FxTextures.BLOB, -aw / 2f - 6f - lineLen / 2f, -0.5f, lineLen / 2f, 0.9f, lc);
        cv.sprite(FxTextures.BLOB, aw / 2f + 6f + lineLen / 2f, -0.5f, lineLen / 2f, 0.9f, lc);
        cv.pop();
        cv.pop();
    }
}
