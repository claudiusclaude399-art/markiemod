package dev.markiemod.griefcheck.data;

import org.bukkit.Material;

import java.util.Locale;

/** Everything GriefCheck can record. The id is what is stored in the database, so never reorder ids. */
public enum Action {
    BREAK(0, "broke", "<red>", Material.IRON_PICKAXE, true),
    PLACE(1, "placed", "<green>", Material.GRASS_BLOCK, true),
    TAKE(2, "took", "<gold>", Material.CHEST, true),
    PUT(3, "put", "<aqua>", Material.CHEST, true),
    EXPLODE(4, "blew up", "<dark_red>", Material.TNT, true),
    BURN(5, "burned", "<#ff7a00>", Material.CAMPFIRE, true),
    IGNITE(6, "ignited", "<#ff7a00>", Material.FLINT_AND_STEEL, false),
    KILL(7, "killed", "<dark_purple>", Material.IRON_SWORD, false);

    public final int id;
    public final String verb;
    public final String color;
    public final Material icon;
    public final boolean rollbackable;

    Action(int id, String verb, String color, Material icon, boolean rollbackable) {
        this.id = id;
        this.verb = verb;
        this.color = color;
        this.icon = icon;
        this.rollbackable = rollbackable;
    }

    public static Action byId(int id) {
        for (Action a : values()) if (a.id == id) return a;
        return BREAK;
    }

    public static Action parse(String s) {
        String k = s.toLowerCase(Locale.ROOT);
        return switch (k) {
            case "break", "broke", "-block" -> BREAK;
            case "place", "placed", "+block" -> PLACE;
            case "take", "took", "-container" -> TAKE;
            case "put", "+container" -> PUT;
            case "explode", "explosion", "tnt" -> EXPLODE;
            case "burn", "fire" -> BURN;
            case "ignite" -> IGNITE;
            case "kill", "kills" -> KILL;
            default -> null;
        };
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
