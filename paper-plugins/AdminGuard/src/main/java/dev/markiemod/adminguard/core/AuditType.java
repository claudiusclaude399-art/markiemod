package dev.markiemod.adminguard.core;

import org.bukkit.Material;

public enum AuditType {
    REQUESTED("<yellow>", Material.CLOCK),
    APPROVED("<green>", Material.LIME_DYE),
    EXECUTED("<green>", Material.LIME_CONCRETE),
    LOGGED("<gray>", Material.PAPER),
    DENIED("<red>", Material.RED_CONCRETE),
    BLOCKED("<dark_red>", Material.BARRIER),
    CANCELLED("<gray>", Material.GRAY_CONCRETE),
    EXPIRED("<gray>", Material.LIGHT_GRAY_CONCRETE),
    PIN_FAILED("<red>", Material.TRIPWIRE_HOOK),
    LOCKED_OUT("<dark_red>", Material.IRON_BARS),
    RATE_LIMITED("<red>", Material.COBWEB),
    BYPASSED("<light_purple>", Material.ENDER_PEARL),
    CONSOLE("<aqua>", Material.COMMAND_BLOCK),
    COMMAND_BLOCK("<dark_red>", Material.CHAIN_COMMAND_BLOCK),
    OP_REVOKED("<dark_red>", Material.WITHER_SKELETON_SKULL),
    PIN_CHANGED("<aqua>", Material.NAME_TAG),
    SETTINGS("<light_purple>", Material.COMPARATOR);

    public final String color;
    public final Material icon;

    AuditType(String color, Material icon) {
        this.color = color;
        this.icon = icon;
    }

    public String pretty() {
        String s = name().replace('_', ' ').toLowerCase();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
