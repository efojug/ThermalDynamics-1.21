package cofh.thermal.dynamics.compat.jei;

import cofh.thermal.dynamics.compat.mekanism.client.jei.ChemicalFilterGhostHandler;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import static cofh.lib.util.constants.ModIds.ID_THERMAL_DYNAMICS;

@JeiPlugin
public final class TDynJeiPlugin implements IModPlugin {

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {

        if (ModList.get().isLoaded("mekanism")) {
            registration.addGhostIngredientHandler(cofh.core.client.gui.ContainerScreenCoFH.class, new ChemicalFilterGhostHandler());
        }
    }

    @Override
    public ResourceLocation getPluginUid() {

        return ResourceLocation.fromNamespaceAndPath(ID_THERMAL_DYNAMICS, "default");
    }

}
