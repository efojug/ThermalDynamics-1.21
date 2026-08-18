package cofh.thermal.dynamics.compat.mekanism.client;

import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalServoAttachmentMenu;
import cofh.thermal.dynamics.compat.mekanism.inventory.ChemicalTurboServoAttachmentMenu;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_DUCT;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_SERVO_ATTACHMENT_CONTAINER;
import static cofh.thermal.dynamics.compat.mekanism.MekanismCompat.CHEMICAL_TURBO_SERVO_ATTACHMENT_CONTAINER;

public final class MekanismClientCompat {

    private MekanismClientCompat() {

    }

    public static void registerRenderLayers(RenderType cutout, RenderType translucent) {

        ItemBlockRenderTypes.setRenderLayer(CHEMICAL_DUCT.get(), renderType -> renderType == cutout || renderType == translucent);
    }

    public static void registerMenuScreens(RegisterMenuScreensEvent event) {

        event.register(CHEMICAL_SERVO_ATTACHMENT_CONTAINER.get(), ChemicalServoAttachmentScreen::new);
        event.register(CHEMICAL_TURBO_SERVO_ATTACHMENT_CONTAINER.get(), ChemicalTurboServoAttachmentScreen::new);
    }

}
