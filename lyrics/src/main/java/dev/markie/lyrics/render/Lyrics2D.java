package dev.markie.lyrics.render;

import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2fStack;

/** Draws one animated lyric line flat on the screen (subtitle mode and the menu's live preview). */
public final class Lyrics2D {
    private Lyrics2D() {}

    /** @param base text scale; @param cx,cy centre of the line on screen */
    public static void draw(DrawContext ctx, LyricsAnimator.Shown s, long pos, float cx, float cy, float base, float extraAlpha) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.scale(base, base);
        LineRenderer.draw(new Canvas.Screen(ctx), s, pos, false, extraAlpha);
        m.popMatrix();
    }
}
