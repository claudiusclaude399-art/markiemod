package dev.markie.lyrics.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Downloads the album cover (JPEG) and turns it into a Minecraft texture. */
public final class CoverArt {
    public static final int SIZE = 64;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final Identifier ID = Identifier.of("markielyrics", "dynamic/cover");

    private static volatile String wantedUrl;
    private static volatile boolean ready = false;
    private static NativeImageBackedTexture texture;
    private static volatile int palA, palB;
    private static volatile boolean hasPal = false;

    /** Two bright, vivid colours picked from the current cover (for the "Album" colour mode). */
    public static boolean hasPalette() { return hasPal && ready; }
    public static int paletteA() { return palA; }
    public static int paletteB() { return palB; }

    private CoverArt() {}

    /** Texture id to draw, or null if no cover is loaded. */
    public static Identifier texture() { return ready ? ID : null; }

    public static void request(String url) {
        wantedUrl = url;
        if (url == null) { ready = false; hasPal = false; return; }
        CompletableFuture.runAsync(() -> {
            try {
                HttpResponse<byte[]> res = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) return;
                BufferedImage src = ImageIO.read(new ByteArrayInputStream(res.body()));
                if (src == null) return;
                int[] argb = new int[SIZE * SIZE];
                for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
                    argb[y * SIZE + x] = 0xFF000000 | src.getRGB(x * src.getWidth() / SIZE, y * src.getHeight() / SIZE);
                }
                int[] pal = palette(argb);
                palA = pal[0];
                palB = pal[1];
                hasPal = true;
                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    if (!url.equals(wantedUrl)) return;
                    NativeImage img = new NativeImage(SIZE, SIZE, false);
                    for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) img.setColorArgb(x, y, argb[y * SIZE + x]);
                    if (texture == null) {
                        texture = new NativeImageBackedTexture(() -> "markielyrics cover", img);
                        mc.getTextureManager().registerTexture(ID, texture);
                    } else {
                        texture.setImage(img);
                        texture.upload();
                    }
                    ready = true;
                });
            } catch (Exception e) {
                System.err.println("[MarkieLyrics] Cover download failed: " + e);
            }
        });
    }

    /** Bucket pixels by hue, weighted by how colourful they are; return the two strongest (brightened). */
    private static int[] palette(int[] argb) {
        float[] weight = new float[12];
        float[][] sum = new float[12][3];
        for (int c : argb) {
            float[] hsv = Colors.toHsv(c & 0xFFFFFF);
            float w = hsv[1] * hsv[1] * hsv[2];
            if (w < 0.02f) continue;
            int b = Math.min(11, (int) (hsv[0] * 12));
            weight[b] += w;
            sum[b][0] += hsv[0] * w; sum[b][1] += hsv[1] * w; sum[b][2] += hsv[2] * w;
        }
        int first = -1, second = -1;
        for (int i = 0; i < 12; i++) {
            if (first < 0 || weight[i] > weight[first]) { second = first; first = i; }
            else if (second < 0 || weight[i] > weight[second]) second = i;
        }
        if (first < 0 || weight[first] <= 0) return new int[]{0xFFFFFF, 0xDDDDEE};
        int a = fromBucket(sum[first], weight[first]);
        int b = second >= 0 && weight[second] > weight[first] * 0.15f ? fromBucket(sum[second], weight[second])
            : Colors.hsv(Colors.toHsv(a)[0] + 0.08f, 0.35f, 1f);
        return new int[]{a, b};
    }

    private static int fromBucket(float[] s, float w) {
        float h = s[0] / w, sat = s[1] / w;
        return Colors.hsv(h, Math.min(0.75f, Math.max(0.35f, sat)), 1f);
    }
}
