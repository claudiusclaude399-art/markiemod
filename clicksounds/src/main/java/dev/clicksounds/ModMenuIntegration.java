package dev.clicksounds;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.clicksounds.gui.ClickSoundsScreen;

/** "Configure" button in Mod Menu (works from the title screen). Only loaded if Mod Menu is installed. */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ClickSoundsScreen::new;
    }
}
