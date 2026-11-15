package dev.pvpmod.analyzer;

import dev.pvpmod.glass.Col;

/** Every check the analyzer runs. Weight = how much the check is trusted in the overall score. */
public enum CheckType {
    REACH("Reach", "R", Col.RED, 1.0f,
        "Hit you from further than the 3.0 block survival limit, after allowing for both pings."),
    KILLAURA("Killaura", "K", Col.PINK, 1.0f,
        "Hit you while looking far away from you, or snapped onto you for a hit and snapped back."),
    AIM("Aim assist", "A", Col.ORANGE, 0.7f,
        "Kept their crosshair locked on you while you strafed faster than a human can track."),
    AUTOCLICKER("Autoclicker", "C", Col.YELLOW, 0.6f,
        "Clicked faster or more evenly than a human can, for several seconds."),
    VELOCITY("Anti-knockback", "V", Col.PURPLE, 0.8f,
        "Barely moved after your hits, with open space behind them."),
    SPEED("Speed", "S", Col.CYAN, 0.9f,
        "Moved faster than sprint-jumping allows, without knockback or explosions to explain it."),
    FLIGHT("Flight", "F", Col.TEAL, 0.9f,
        "Floated or rose in the air with nothing under them, ignoring gravity."),
    AUTO_TOTEM("Auto totem", "T", Col.YELLOW, 0.9f,
        "Had a new totem in hand faster than human reaction time after a pop, after removing their ping."),
    CRYSTAL_AURA("Crystal aura", "X", Col.PURPLE, 0.8f,
        "Placed and broke end crystals at inhuman speed, or placed them without looking."),
    WALL_HIT("Through walls", "W", Col.BLUE, 0.5f,
        "Hit you with solid blocks between their eyes and your hitbox.");

    public final String title, letter, description;
    public final int color;
    public final float weight;

    CheckType(String title, String letter, int color, float weight, String description) {
        this.title = title;
        this.letter = letter;
        this.color = color;
        this.weight = weight;
        this.description = description;
    }
}
