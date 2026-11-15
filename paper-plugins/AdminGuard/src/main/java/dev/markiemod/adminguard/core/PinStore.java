package dev.markiemod.adminguard.core;

import org.bukkit.configuration.file.YamlConfiguration;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;

/** Stores salted PBKDF2 hashes of staff PINs in pins.yml. The PINs themselves are never saved. */
public final class PinStore {

    private static final int ITERATIONS = 20_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final File file;
    private final Logger log;
    private YamlConfiguration yaml;

    public PinStore(File file, Logger log) {
        this.file = file;
        this.log = log;
        this.yaml = YamlConfiguration.loadConfiguration(file);
    }

    public void reload() {
        yaml = YamlConfiguration.loadConfiguration(file);
    }

    public boolean has(UUID uuid) {
        return yaml.isString(uuid.toString());
    }

    public void set(UUID uuid, String pin) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        yaml.set(uuid.toString(), ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":"
                + Base64.getEncoder().encodeToString(hash(pin, salt, ITERATIONS)));
        save();
    }

    public void remove(UUID uuid) {
        yaml.set(uuid.toString(), null);
        save();
    }

    public boolean check(UUID uuid, String pin) {
        String stored = yaml.getString(uuid.toString());
        if (stored == null) return false;
        String[] parts = stored.split(":");
        if (parts.length != 3) return false;
        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expected = Base64.getDecoder().decode(parts[2]);
            return MessageDigest.isEqual(expected, hash(pin, salt, iterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] hash(String pin, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }

    private void save() {
        try {
            yaml.options().setHeader(java.util.List.of("AdminGuard PIN hashes (PBKDF2). Delete a line to reset that player's PIN."));
            yaml.save(file);
        } catch (IOException e) {
            log.warning("Could not save pins.yml: " + e.getMessage());
        }
    }
}
