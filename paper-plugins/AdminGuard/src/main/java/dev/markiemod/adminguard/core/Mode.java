package dev.markiemod.adminguard.core;

import org.bukkit.Material;

/** How strictly a protected command is guarded. Ordered from weakest to strongest. */
public enum Mode {
    LOG("Log only", "<gray>", Material.PAPER, "Runs normally, but is logged and staff are notified."),
    CONFIRM("Confirm", "<green>", Material.LIME_WOOL, "Shows a confirm screen first (stops accidents)."),
    PIN("PIN", "<yellow>", Material.YELLOW_WOOL, "The player must enter their personal PIN."),
    APPROVAL("Approval", "<gold>", Material.ORANGE_WOOL, "Another staff member must approve it."),
    PIN_AND_APPROVAL("PIN + Approval", "<red>", Material.RED_WOOL, "PIN, then another staff member must approve."),
    BLOCK("Blocked", "<dark_red>", Material.BLACK_WOOL, "Can never be run in-game.");

    public final String display;
    public final String color;
    public final Material icon;
    public final String description;

    Mode(String display, String color, Material icon, String description) {
        this.display = display;
        this.color = color;
        this.icon = icon;
        this.description = description;
    }

    public boolean needsPin() {
        return this == PIN || this == PIN_AND_APPROVAL;
    }

    public boolean needsApproval() {
        return this == APPROVAL || this == PIN_AND_APPROVAL;
    }

    public Mode next(boolean back) {
        Mode[] v = values();
        return v[Math.floorMod(ordinal() + (back ? -1 : 1), v.length)];
    }
}
