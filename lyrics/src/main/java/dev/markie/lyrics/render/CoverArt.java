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

    private CoverArt() {}

    /** Texture id to draw, or null if no cover is loaded. */
    public static Identifier texture() { return ready ? ID : null; }

    public static void request(String url) {
        wantedUrl = url;
        if (url == null) { ready = false; return; }
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
}
