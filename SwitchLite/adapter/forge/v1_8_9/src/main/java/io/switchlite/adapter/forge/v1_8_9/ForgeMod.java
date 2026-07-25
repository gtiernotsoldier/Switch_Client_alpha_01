package io.switchlite.adapter.forge.v1_8_9;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;

/**
 * Forge 1.8.9 mod entry point.
 * Discovered by Forge's classpath scanner via @Mod annotation.
 * Delegates all initialization to {@link ForgeBootstrap}.
 */
@Mod(
    modid = "switchlite",
    name = "SwitchLite",
    version = "0.1.0-alpha",
    acceptedMinecraftVersions = "[1.8.9]"
)
public class ForgeMod {

    @Mod.EventHandler
    public void onInit(FMLInitializationEvent event) {
        ForgeBootstrap.INSTANCE.init();
    }
}
