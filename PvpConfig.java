package dev.pvpmod.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.pvpmod.PvpTint;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.particle.AnimatedParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PvpConfig {
    public static final String TOTEM_PARTICLE = "minecraft:totem_of_undying";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("pvpmod.json");
    private static PvpConfig instance = new PvpConfig();

    public float handScale = 1.0f;
    public Map<String, Float> itemScales = new HashMap<>();
    public Map<String, Integer> itemColors = new HashMap<>();   // RGB 0xRRGGBB per item id

    public boolean crystal3d = true;
    public boolean crystalBase = true;

    public boolean totemOverlay = true;
    public boolean totemParticles = true;
    public float totemParticleScale = 1.0f;
    public float totemParticleDensity = 1.0f;
    public boolean totemCustomColor = false;
    public float totemR = 1.0f, totemG = 0.85f, totemB = 0.1f;

    public boolean particlesEnabled = true;
    public float particleScale = 1.0f;
    public Set<String> hiddenParticles = new HashSet<>();
    public Map<String, Float> particleScales = new HashMap<>();
    public float particleDensity = 1.0f;                                  // global quantity 0..1
    public Map<String, Float> particleDensities = new HashMap<>();       // per-type quantity 0..1

    public static PvpConfig get() { return instance; }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE)) {
                    PvpConfig c = GSON.fromJson(r, PvpConfig.class);
                    if (c != null) { c.sanitize(); instance = c; }
                }
            } else {
                save();
            }
        } catch (Exception e) {
            System.err.println("[PvpMod] Could not read config, using defaults: " + e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(instance, w);
            }
        } catch (IOException e) {
            System.err.println("[PvpMod] Could not save config: " + e);
        }
    }

    private void sanitize() {
        if (itemScales == null) itemScales = new HashMap<>();
        if (itemColors == null) itemColors = new HashMap<>();
        if (hiddenParticles == null) hiddenParticles = new HashSet<>();
        if (particleScales == null) particleScales = new HashMap<>();
        if (particleDensities == null) particleDensities = new HashMap<>();
    }

    public float scaleFor(ItemStack stack) {
        if (stack.isEmpty()) return 1.0f;
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        float s = handScale * itemScales.getOrDefault(id, 1.0f);
        return Math.max(0.05f, Math.min(4.0f, s));
    }

    public int colorFor(ItemStack stack) {
        if (stack.isEmpty()) return PvpTint.NONE;
        Integer c = itemColors.get(Registries.ITEM.getId(stack.getItem()).toString());
        return c == null ? PvpTint.NONE : (c & 0xFFFFFF);
    }

    private static String idOf(ParticleEffect effect) {
        return Registries.PARTICLE_TYPE.getId(effect.getType()).toString();
    }

    public boolean shouldSpawn(ParticleEffect effect) {
        if (!particlesEnabled) return false;
        String id = idOf(effect);
        if (hiddenParticles.contains(id)) return false;
        float chance = particleDensity * particleDensities.getOrDefault(id, 1.0f);
        if (id.equals(TOTEM_PARTICLE)) {
            if (!totemParticles) return false;
            chance *= totemParticleDensity;
        }
        return chance >= 1.0f || Math.random() < chance;
    }

    public void stylise(ParticleEffect effect, Particle particle) {
        String id = idOf(effect);
        float s = particleScale * particleScales.getOrDefault(id, 1.0f);
        if (id.equals(TOTEM_PARTICLE)) {
            s *= totemParticleScale;
            if (totemCustomColor && particle instanceof AnimatedParticle animated) {
                int rgb = (Math.round(totemR * 255) << 16) | (Math.round(totemG * 255) << 8) | Math.round(totemB * 255);
                animated.setColor(rgb);
            }
        }
        if (s != 1.0f) particle.scale(Math.max(0.05f, s));
    }
}
