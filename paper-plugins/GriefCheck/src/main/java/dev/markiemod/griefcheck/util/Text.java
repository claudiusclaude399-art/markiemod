package dev.markiemod.griefcheck.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Text() {
    }

    public static Component mm(String text, TagResolver... resolvers) {
        return MM.deserialize(text, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Escapes user-provided text so it cannot inject MiniMessage tags. */
    public static String esc(String text) {
        return text == null ? "" : MM.escapeTags(text);
    }
}
