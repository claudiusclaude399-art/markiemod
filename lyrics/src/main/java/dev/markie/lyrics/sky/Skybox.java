package dev.markie.lyrics.sky;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.markie.lyrics.config.LyricsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Generates the chosen sky as a cube texture (in the background) and draws it in place of the vanilla sky. */
public final class Skybox {
    private static final int FACE = 512;
    private static final Identifier TEX_ID = Identifier.of("markielyrics", "dynamic/skybox");
    private static final ExecutorService GEN = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MarkieLyrics-Sky");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static SkyTexture texture;
    private static int loadedSky = -1;
    private static boolean loaded3d;
    private static volatile int generating = -1;
    private static volatile int horizonRgb = 0x8090A0;
    private static GpuBuffer box;

    // previews for the menu
    public static final int PW = 96, PH = 54;
    private static final Identifier[] PREVIEW_IDS = new Identifier[SkyLibrary.ALL.length];
    private static volatile boolean previewsRequested = false;

    private Skybox() {}

    /** True if the custom sky should replace the vanilla one this frame. */
    public static boolean active() {
        LyricsConfig c = LyricsConfig.get();
        if (!c.skyEnabled) return false;
        ensureTexture(c.sky);
        return loadedSky >= 0 && texture != null;
    }

    public static int horizonColor() { return horizonRgb; }

    /** Called from the sky renderer mixin, on the render thread, with the sky's model-view set up. */
    public static void render() {
        LyricsConfig c = LyricsConfig.get();
        if (box == null) box = buildBox();
        MinecraftClient mc = MinecraftClient.getInstance();
        float spin = (float) ((System.currentTimeMillis() % 3_600_000L) / 1000.0 * c.skySpin * Math.PI / 180.0);
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).rotateY(spin);
        float b = c.skyBrightness;
        float r = b, g = b, bl = b;
        if (c.reactSky) {
            // the sky breathes with the music: brighter on each beat, tinted with the lyric colour on the bar
            float str = dev.markie.lyrics.music.MusicPulse.strength();
            float k = 1f + str * (0.22f * dev.markie.lyrics.music.MusicPulse.beat() + 0.08f * dev.markie.lyrics.music.MusicPulse.energy());
            int tint = dev.markie.lyrics.render.Colors.glow();
            float tk = 0.35f * str * dev.markie.lyrics.music.MusicPulse.bar();
            r = b * k * (1f + tk * (((tint >> 16) & 255) / 255f - 0.5f));
            g = b * k * (1f + tk * (((tint >> 8) & 255) / 255f - 0.5f));
            bl = b * k * (1f + tk * ((tint & 255) / 255f - 0.5f));
        }
        if (dev.markie.lyrics.render.EnvColors.active()) {
            // multiply the sky picture by the cover colour (normalised so brightness stays about the same)
            int m = dev.markie.lyrics.render.EnvColors.main();
            float mr = ((m >> 16) & 255) / 255f, mg = ((m >> 8) & 255) / 255f, mb = (m & 255) / 255f;
            float mx = Math.max(0.05f, Math.max(mr, Math.max(mg, mb)));
            float k = 0.6f * dev.markie.lyrics.render.EnvColors.amount();
            r *= 1f + (mr / mx * 1.1f - 1f) * k;
            g *= 1f + (mg / mx * 1.1f - 1f) * k;
            bl *= 1f + (mb / mx * 1.1f - 1f) * k;
        }
        GpuBufferSlice uniforms = RenderSystem.getDynamicUniforms().write(mv, new Vector4f(r, g, bl, 1f), new Vector3f(), new Matrix4f());
        RenderSystem.ShapeIndexBuffer quads = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
        GpuBuffer indices = quads.getIndexBuffer(72);
        Framebuffer fb = mc.getFramebuffer();
        // build this frame's 3D sky objects before the render pass opens
        GpuBuffer sceneVb = null, sceneIdx = null;
        int sceneCount = 0;
        try (BuiltBuffer scene = Sky3D.build(loadedSky, spin)) {
            if (scene != null) {
                sceneCount = Sky3D.indexCount();
                sceneVb = RenderSystem.getDevice().createBuffer(() -> "Markie Lyrics 3D sky", 40, scene.getBuffer());
                sceneIdx = quads.getIndexBuffer(sceneCount);
            }
        }
        if (Sky3D.atlas() == null) sceneVb = null;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Markie Lyrics skybox",
                fb.getColorAttachmentView(), OptionalInt.empty(), fb.getDepthAttachmentView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderPipelines.POSITION_TEX_COLOR_END_SKY);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", uniforms);
            pass.bindTexture("Sampler0", texture.getGlTextureView(), texture.getSampler());
            pass.setVertexBuffer(0, box);
            pass.setIndexBuffer(indices, quads.getIndexType());
            pass.drawIndexed(0, 0, 72, 1);

            // 3D objects on top of the backdrop
            if (sceneVb != null) {
                pass.bindTexture("Sampler0", Sky3D.atlas().getGlTextureView(), Sky3D.atlas().getSampler());
                pass.setVertexBuffer(0, sceneVb);
                pass.setIndexBuffer(sceneIdx, quads.getIndexType());
                pass.drawIndexed(0, 0, sceneCount, 1);
            }
        } finally {
            if (sceneVb != null) sceneVb.close();
        }
    }

    // ------------------------------------------------------------------ cube texture

    /** Face order in the 3x2 atlas: +X, -X, +Y / -Y, +Z, -Z. */
    private static void faceDir(int face, float u, float v, float[] out) {
        switch (face) {
            case 0 -> { out[0] = 1; out[1] = -v; out[2] = -u; }
            case 1 -> { out[0] = -1; out[1] = -v; out[2] = u; }
            case 2 -> { out[0] = u; out[1] = 1; out[2] = v; }
            case 3 -> { out[0] = u; out[1] = -1; out[2] = -v; }
            case 4 -> { out[0] = u; out[1] = -v; out[2] = 1; }
            default -> { out[0] = -u; out[1] = -v; out[2] = -1; }
        }
    }

    private static void ensureTexture(int sky) {
        boolean want3d = LyricsConfig.get().sky3d;
        int key = sky * 2 + (want3d ? 1 : 0);
        if ((loadedSky == sky && loaded3d == want3d) || generating == key) return;
        generating = key;
        SkyLibrary.Fn fn = SkyLibrary.ALL[sky].fn();
        CompletableFuture.supplyAsync(() -> {
            SkyLibrary.threeD = want3d;
            int[] px = new int[FACE * 3 * FACE * 2];
            float[] d = new float[3];
            for (int face = 0; face < 6; face++) {
                int ox = (face % 3) * FACE, oy = (face / 3) * FACE;
                for (int j = 0; j < FACE; j++) {
                    float v = 2f * (j + 0.5f) / FACE - 1f;
                    for (int i = 0; i < FACE; i++) {
                        float u = 2f * (i + 0.5f) / FACE - 1f;
                        faceDir(face, u, v, d);
                        float l = (float) Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                        px[(oy + j) * FACE * 3 + ox + i] = 0xFF000000 | fn.color(d[0] / l, d[1] / l, d[2] / l);
                    }
                }
            }
            // average horizon colour, used to tint the fog so the world blends into the sky
            float r = 0, g = 0, b = 0;
            for (int k = 0; k < 64; k++) {
                double a = k * Math.PI * 2 / 64;
                int c = fn.color((float) Math.sin(a) * 0.999f, 0.04f, (float) Math.cos(a) * 0.999f);
                r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
            }
            horizonRgb = (Math.round(r / 64) << 16) | (Math.round(g / 64) << 8) | Math.round(b / 64);
            return px;
        }, GEN).thenAccept(px -> MinecraftClient.getInstance().execute(() -> {
            if (generating != key) return;
            NativeImage img = new NativeImage(FACE * 3, FACE * 2, false);
            for (int y = 0; y < FACE * 2; y++) for (int x = 0; x < FACE * 3; x++) img.setColorArgb(x, y, px[y * FACE * 3 + x]);
            if (texture == null) {
                texture = new SkyTexture(img);
                MinecraftClient.getInstance().getTextureManager().registerTexture(TEX_ID, texture);
            } else {
                texture.setImage(img);
                texture.upload();
            }
            loadedSky = sky;
            loaded3d = want3d;
            generating = -1;
        }));
    }

    private static GpuBuffer buildBox() {
        BufferAllocator alloc = BufferAllocator.fixedSized(48 * VertexFormats.POSITION_TEXTURE_COLOR.getVertexSize());
        BufferBuilder bb = new BufferBuilder(alloc, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE_COLOR);
        float[] d = new float[3];
        float[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        float inset = 0.5f / FACE;
        for (int face = 0; face < 6; face++) {
            float ox = (face % 3) / 3f, oy = (face / 3) / 2f;
            // each face twice (both windings) so it shows regardless of culling
            for (int pass = 0; pass < 2; pass++) {
                for (int k = 0; k < 4; k++) {
                    float[] cn = corners[pass == 0 ? k : 3 - k];
                    faceDir(face, cn[0], cn[1], d);
                    float tu = (cn[0] + 1) / 2f, tv = (cn[1] + 1) / 2f;
                    tu = inset + tu * (1 - 2 * inset);
                    tv = inset + tv * (1 - 2 * inset);
                    bb.vertex(d[0] * 100f, d[1] * 100f, d[2] * 100f).texture(ox + tu / 3f, oy + tv / 2f).color(-1);
                }
            }
        }
        try (BuiltBuffer built = bb.end()) {
            return RenderSystem.getDevice().createBuffer(() -> "Markie Lyrics skybox", 40, built.getBuffer());
        } finally {
            alloc.close();
        }
    }

    // ------------------------------------------------------------------ menu previews

    /** Preview texture for a sky, or null while they are still being made. */
    public static Identifier preview(int i) {
        if (!previewsRequested) {
            previewsRequested = true;
            for (int k = 0; k < SkyLibrary.ALL.length; k++) {
                final int idx = k;
                CompletableFuture.supplyAsync(() -> makePreview(SkyLibrary.ALL[idx].fn()), GEN)
                    .thenAccept(px -> MinecraftClient.getInstance().execute(() -> {
                        NativeImage img = new NativeImage(PW, PH, false);
                        for (int y = 0; y < PH; y++) for (int x = 0; x < PW; x++) img.setColorArgb(x, y, px[y * PW + x]);
                        Identifier id = Identifier.of("markielyrics", "dynamic/sky_preview_" + idx);
                        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new SkyTexture(img));
                        PREVIEW_IDS[idx] = id;
                    }));
            }
        }
        return PREVIEW_IDS[i];
    }

    private static int[] makePreview(SkyLibrary.Fn fn) {
        int[] px = new int[PW * PH];
        float pitch = (float) Math.toRadians(18);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);
        float tanH = (float) Math.tan(Math.toRadians(55));
        for (int y = 0; y < PH; y++) for (int x = 0; x < PW; x++) {
            float vx = (2f * (x + 0.5f) / PW - 1f) * tanH;
            float vy = (1f - 2f * (y + 0.5f) / PH) * tanH * PH / PW;
            float vz = 1f;
            // tilt up a little
            float ry = vy * cp + vz * sp, rz = -vy * sp + vz * cp;
            float l = (float) Math.sqrt(vx * vx + ry * ry + rz * rz);
            px[y * PW + x] = 0xFF000000 | fn.color(vx / l, ry / l, rz / l);
        }
        return px;
    }

    private static final class SkyTexture extends NativeImageBackedTexture {
        SkyTexture(NativeImage img) {
            super(() -> "markielyrics sky", img);
            this.sampler = RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
        }
    }
}
