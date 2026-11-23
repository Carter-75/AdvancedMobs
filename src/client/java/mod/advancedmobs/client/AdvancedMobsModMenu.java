package mod.advancedmobs.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class AdvancedMobsModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // ModMenu opens the (rebuilt, native) settings screen directly.
        return parent -> AdvancedMobsConfigScreen.create(parent);
    }
}
